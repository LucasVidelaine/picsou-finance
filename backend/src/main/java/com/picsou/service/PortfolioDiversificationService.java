package com.picsou.service;

import com.picsou.dto.DiversificationResponse;
import com.picsou.dto.HoldingResponse;
import com.picsou.dto.WeightedSlice;
import com.picsou.model.*;
import com.picsou.repository.AccountHoldingRepository;
import com.picsou.repository.HoldingClassificationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Spreads the equity sleeve across sectors and regions.
 *
 * <p>Reads only persisted profiles — never the network. A ticker nobody has warmed yet counts as
 * unclassified and is listed in {@code unclassified}, which is honest and keeps a page render off
 * an unofficial HTML endpoint. The list carries enough to correct the line by hand, because the
 * securities that land there are largely the ones no provider will ever resolve.
 */
@Service
@Transactional(readOnly = true)
public class PortfolioDiversificationService {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final int SCALE = 8;

    /**
     * How many sectors and regions count as diversified.
     *
     * <p>Eleven sectors exist, but a portfolio spread evenly over eleven is a closet index fund;
     * six effective sectors is where concentration stops being the dominant risk. Three regions
     * is the equivalent for geography, where the choice is realistically US / Europe / Asia.
     */
    private static final int SECTOR_TARGET = 6;
    private static final int COUNTRY_TARGET = 3;

    private final AccountAccessResolver accessResolver;
    private final AccountService accountService;
    private final AccountHoldingRepository holdingRepository;
    private final HoldingClassificationRepository classificationRepository;
    private final SecurityProfileService securityProfileService;

    public PortfolioDiversificationService(AccountAccessResolver accessResolver,
                                           AccountService accountService,
                                           AccountHoldingRepository holdingRepository,
                                           HoldingClassificationRepository classificationRepository,
                                           SecurityProfileService securityProfileService) {
        this.accessResolver = accessResolver;
        this.accountService = accountService;
        this.holdingRepository = holdingRepository;
        this.classificationRepository = classificationRepository;
        this.securityProfileService = securityProfileService;
    }

    public DiversificationResponse diversification(Long memberId) {
        List<Account> accounts = accessResolver.readableAccounts(memberId);
        Map<Long, BigDecimal> shares = accessResolver.sharesFor(accounts, memberId);

        Map<String, HoldingClassification> overrides = new HashMap<>();
        for (HoldingClassification c : classificationRepository.findByMemberId(memberId)) {
            overrides.put(c.getTicker().toUpperCase(Locale.ROOT), c);
        }

        // One pass to collect the equity lines, so profiles can be loaded in a single query.
        Map<String, BigDecimal> valueByTicker = new LinkedHashMap<>();
        // Where each ticker was first seen. Kept only so an unclassified line can be named and
        // linked back to an account the member owns; the classification itself is per-ticker.
        Map<String, HoldingOrigin> originByTicker = new HashMap<>();
        BigDecimal total = BigDecimal.ZERO;

        for (Account account : accounts) {
            if (WealthTier.of(account.getType()) != WealthTier.EQUITY) continue;
            if (holdingRepository.findByAccount_Id(account.getId()).isEmpty()) continue;

            BigDecimal share = shares.get(account.getId());
            for (HoldingResponse line : accountService.getHoldings(account.getId(), memberId)) {
                if (line.currentValueEur() == null || line.ticker() == null) continue;
                BigDecimal value = AccountAccessResolver.weigh(line.currentValueEur(), share);
                if (value.signum() == 0) continue;
                // The same ticker held in two accounts is one position for this purpose.
                String ticker = line.ticker().toUpperCase(Locale.ROOT);
                valueByTicker.merge(ticker, value, BigDecimal::add);
                originByTicker.putIfAbsent(ticker,
                    new HoldingOrigin(account.getId(), line.name()));
                total = total.add(value);
            }
        }

        Map<String, SecurityProfile> profiles = securityProfileService.load(valueByTicker.keySet());

        Map<String, BigDecimal> sectorTotals = new HashMap<>();
        Map<String, BigDecimal> countryTotals = new HashMap<>();
        List<DiversificationResponse.UnclassifiedLine> unclassifiedLines = new ArrayList<>();
        BigDecimal sectorClassified = BigDecimal.ZERO;
        BigDecimal countryClassified = BigDecimal.ZERO;
        boolean sectorMixed = false;
        boolean countryMixed = false;

        for (Map.Entry<String, BigDecimal> entry : valueByTicker.entrySet()) {
            String ticker = entry.getKey();
            BigDecimal value = entry.getValue();
            HoldingClassification override = overrides.get(ticker);
            SecurityProfile profile = profiles.get(ticker);

            boolean placedSector = false;
            boolean placedCountry = false;

            // The user's verdict beats anything a provider inferred, per field.
            if (override != null && override.getSectorKey() != null) {
                sectorTotals.merge(override.getSectorKey(), value, BigDecimal::add);
                sectorClassified = sectorClassified.add(value);
                sectorMixed = true;
                placedSector = true;
            }
            if (override != null && override.getCountryKey() != null) {
                countryTotals.merge(override.getCountryKey(), value, BigDecimal::add);
                countryClassified = countryClassified.add(value);
                countryMixed = true;
                placedCountry = true;
            }

            if (profile == null) {
                // Never looked up, so whatever the override did not cover is missing.
                // profileLooked=false is what lets the UI offer a refresh instead of sending
                // someone to fill in a form the scheduler would have filled itself.
                //
                // Guarded, because an override can cover both axes on its own: a security no
                // provider carries — an employee-savings FCPE — is fully classified by hand and
                // has no profile, and listing it again would make the correction look ignored.
                if (!placedSector || !placedCountry) {
                    record(unclassifiedLines, originByTicker, ticker, value,
                        !placedSector, !placedCountry, false);
                }
                continue;
            }

            List<SecurityCompositionSlice> slices = profile.getSlices();
            if (!placedSector) {
                BigDecimal placed = spread(slices, SecuritySliceKind.SECTOR, value, sectorTotals);
                if (placed.signum() > 0) {
                    sectorClassified = sectorClassified.add(placed);
                    placedSector = true;
                } else if (profile.getSectorKey() != null) {
                    sectorTotals.merge(profile.getSectorKey(), value, BigDecimal::add);
                    sectorClassified = sectorClassified.add(value);
                    // A single share contributes one sector; mixing it with a fund's
                    // look-through is a different quantity, and the UI says so.
                    sectorMixed = true;
                    placedSector = true;
                }
            }
            if (!placedCountry) {
                BigDecimal placed = spread(slices, SecuritySliceKind.COUNTRY, value, countryTotals);
                if (placed.signum() > 0) {
                    countryClassified = countryClassified.add(placed);
                    placedCountry = true;
                } else if (profile.getCountryKey() != null) {
                    countryTotals.merge(profile.getCountryKey(), value, BigDecimal::add);
                    countryClassified = countryClassified.add(value);
                    countryMixed = true;
                    placedCountry = true;
                }
            }

            // Looked up and still short of one axis: a profile exists, so this one is only fixable
            // by hand. Reported per axis because a share often has a sector and no domicile.
            if (!placedSector || !placedCountry) {
                record(unclassifiedLines, originByTicker, ticker, value,
                    !placedSector, !placedCountry, true);
            }
        }

        // A holding counts as classified when *either* breakdown could place it; the two are
        // reported separately below, so the headline coverage is the more generous of the two.
        BigDecimal classified = sectorClassified.max(countryClassified);
        BigDecimal unclassified = total.subtract(classified).max(BigDecimal.ZERO);

        return new DiversificationResponse(
            scale2(total),
            scale2(classified),
            scale2(unclassified),
            scale2(percentOf(classified, total)),
            // Biggest first: the line worth correcting by hand is the one moving the most money.
            unclassifiedLines.stream()
                .sorted(Comparator.comparing(
                    DiversificationResponse.UnclassifiedLine::valueEur).reversed())
                .toList(),
            breakdown(sectorTotals, sectorClassified, SECTOR_TARGET, sectorMixed),
            breakdown(countryTotals, countryClassified, COUNTRY_TARGET, countryMixed)
        );
    }

    /** Where a ticker was first seen, so an unclassified line can be named and reached. */
    private record HoldingOrigin(Long accountId, String name) {}

    private static void record(List<DiversificationResponse.UnclassifiedLine> into,
                               Map<String, HoldingOrigin> origins, String ticker,
                               BigDecimal value, boolean sectorMissing, boolean countryMissing,
                               boolean profileLooked) {
        HoldingOrigin origin = origins.get(ticker);
        into.add(new DiversificationResponse.UnclassifiedLine(
            ticker,
            origin == null ? null : origin.name(),
            origin == null ? null : origin.accountId(),
            scale2(value),
            sectorMissing,
            countryMissing,
            profileLooked));
    }

    /**
     * Distributes one holding's value across an ETF's look-through slices, weighted by their
     * percentages and <em>renormalised to what the provider actually published</em>.
     *
     * <p>A top-ten breakdown does not sum to 100, so treating the published percentages as
     * absolute would silently drop the remainder into "unclassified" for every fund. Scaling to
     * the published total keeps the shares proportional and honest about the fund being fully
     * placed — the coverage the user must watch is the ticker-level one, not this.
     */
    private static BigDecimal spread(List<SecurityCompositionSlice> slices, SecuritySliceKind kind,
                                     BigDecimal value, Map<String, BigDecimal> target) {
        BigDecimal published = BigDecimal.ZERO;
        for (SecurityCompositionSlice slice : slices) {
            if (slice.getKind() == kind) published = published.add(slice.getPercent());
        }
        if (published.signum() <= 0) return BigDecimal.ZERO;

        for (SecurityCompositionSlice slice : slices) {
            if (slice.getKind() != kind) continue;
            BigDecimal part = value.multiply(slice.getPercent())
                .divide(published, SCALE, RoundingMode.HALF_UP);
            target.merge(slice.getLabel(), part, BigDecimal::add);
        }
        return value;
    }

    private static DiversificationResponse.Breakdown breakdown(Map<String, BigDecimal> totals,
                                                               BigDecimal classified,
                                                               int target,
                                                               boolean mixed) {
        if (classified.signum() <= 0) {
            return new DiversificationResponse.Breakdown(
                0, BigDecimal.ZERO, target, mixed ? "MIXED" : "EXPOSURE", List.of());
        }

        List<WeightedSlice> slices = totals.entrySet().stream()
            .map(e -> new WeightedSlice(e.getKey(), scale2(percentOf(e.getValue(), classified))))
            .sorted(Comparator.comparing(WeightedSlice::percent).reversed())
            .toList();

        // Inverse Herfindahl: the effective number of positions. Two portfolios can hold five
        // sectors and be nothing alike — 20/20/20/20/20 is five, 96/1/1/1/1 is barely one — and
        // counting buckets cannot tell them apart.
        BigDecimal herfindahl = BigDecimal.ZERO;
        for (BigDecimal value : totals.values()) {
            BigDecimal weight = value.divide(classified, SCALE, RoundingMode.HALF_UP);
            herfindahl = herfindahl.add(weight.multiply(weight));
        }
        BigDecimal effective = herfindahl.signum() == 0
            ? BigDecimal.ZERO
            : BigDecimal.ONE.divide(herfindahl, SCALE, RoundingMode.HALF_UP);

        int score = effective.divide(BigDecimal.valueOf(target), SCALE, RoundingMode.HALF_UP)
            .multiply(HUNDRED).min(HUNDRED)
            .setScale(0, RoundingMode.HALF_UP).intValue();

        return new DiversificationResponse.Breakdown(
            score, effective.setScale(2, RoundingMode.HALF_UP), target,
            mixed ? "MIXED" : "EXPOSURE", slices);
    }

    private static BigDecimal percentOf(BigDecimal value, BigDecimal total) {
        if (total == null || total.signum() == 0) return BigDecimal.ZERO;
        return value.divide(total, SCALE, RoundingMode.HALF_UP).multiply(HUNDRED);
    }

    private static BigDecimal scale2(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }
}
