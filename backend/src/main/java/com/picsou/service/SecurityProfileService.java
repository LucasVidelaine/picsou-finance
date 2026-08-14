package com.picsou.service;

import com.picsou.dto.EquityProfile;
import com.picsou.dto.EtfComposition;
import com.picsou.dto.SecurityInsightResponse;
import com.picsou.dto.WeightedSlice;
import com.picsou.model.SecurityCompositionSlice;
import com.picsou.model.SecurityProfile;
import com.picsou.model.SecuritySliceKind;
import com.picsou.port.EquityProfileProvider;
import com.picsou.repository.SecurityProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Owns the durable cache of what each security is.
 *
 * <p>Two paths, deliberately kept apart: {@link #load} reads the table and nothing else, so
 * rendering a breakdown can never block on a scrape; {@link #refresh} does the network work and
 * is called only by the scheduler.
 *
 * <p>{@code SecurityInsightService} still answers the single-ticker {@code /insight} endpoint
 * from its own in-memory cache. That is one ticker, user-initiated, and fine — replacing it is a
 * separate change with its own tests.
 */
@Service
public class SecurityProfileService {

    private static final Logger log = LoggerFactory.getLogger(SecurityProfileService.class);

    /** A sector does not change. Anything older than this is refreshed by the weekly pass. */
    public static final Duration REFRESH_AFTER = Duration.ofDays(30);

    private final SecurityProfileRepository repository;
    private final SecurityInsightService insightService;
    private final SecurityIdentityService identityService;
    private final List<EquityProfileProvider> equityProviders;

    public SecurityProfileService(SecurityProfileRepository repository,
                                  SecurityInsightService insightService,
                                  SecurityIdentityService identityService,
                                  List<EquityProfileProvider> equityProviders) {
        this.repository = repository;
        this.insightService = insightService;
        this.identityService = identityService;
        this.equityProviders = equityProviders;
    }

    /** Persisted profiles for the tickers asked for, keyed uppercase. Never fetches. */
    @Transactional(readOnly = true)
    public Map<String, SecurityProfile> load(Collection<String> tickers) {
        Set<String> upper = tickers.stream()
            .filter(Objects::nonNull)
            .map(t -> t.toUpperCase(Locale.ROOT))
            .collect(Collectors.toSet());
        if (upper.isEmpty()) return Map.of();
        return repository.findAllWithSlicesByTickerIn(upper).stream()
            .collect(Collectors.toMap(p -> p.getTicker().toUpperCase(Locale.ROOT), p -> p));
    }

    /**
     * Resolves one ticker from the network and stores the answer.
     *
     * <p>Asset type and ETF composition come from {@link SecurityInsightService}, so the two
     * paths can never disagree about what a security is. A single share additionally goes
     * through the {@link EquityProfileProvider}s, merged <strong>field by field</strong> — no one
     * source has both halves, so first-provider-wins would throw away the country whenever the
     * sector arrived first.
     */
    @Transactional
    public SecurityProfile refresh(String ticker) {
        String upper = ticker.toUpperCase(Locale.ROOT);

        SecurityProfile profile = repository.findByTicker(upper)
            .orElseGet(() -> SecurityProfile.builder().ticker(upper).build());

        // The ISIN a sync recorded, or the ticker itself when it is one. Passing it on is what
        // lets Boursorama resolve a fund whose ticker OpenFIGI picked from a US OTC listing.
        String isin = identityService.isinOf(upper).orElse(null);
        if (isin != null && profile.getIsin() == null) {
            profile.setIsin(isin);
        }
        SecurityInsightResponse insight = insightService.getInsight(upper, null, isin);
        profile.setAssetType(insight.assetType());
        profile.setRefreshedAt(Instant.now());

        EtfComposition composition = insight.composition();
        if (composition != null) {
            profile.setSource(composition.source());
            profile.setAsOf(composition.asOf());
            profile.replaceSlices(slicesOf(composition));
            profile.setSectorKey(null);
            profile.setCountryKey(null);
        } else if ("STOCK".equals(insight.assetType())) {
            EquityProfile merged = resolveEquity(upper);
            profile.setSectorKey(merged.sectorKey());
            profile.setCountryKey(merged.countryKey());
            profile.setSource(merged.source());
            profile.replaceSlices(List.of());
        } else {
            profile.replaceSlices(List.of());
        }

        return repository.save(profile);
    }

    /** Refreshes the stalest tickers, capped. Returns how many were actually refreshed. */
    @Transactional
    public int refreshStale(Collection<String> tickers, int limit) {
        Map<String, SecurityProfile> known = load(tickers);
        Instant cutoff = Instant.now().minus(REFRESH_AFTER);

        List<String> due = tickers.stream()
            .filter(Objects::nonNull)
            .map(t -> t.toUpperCase(Locale.ROOT))
            .distinct()
            .filter(t -> {
                SecurityProfile p = known.get(t);
                return p == null || p.getRefreshedAt() == null || p.getRefreshedAt().isBefore(cutoff);
            })
            .limit(limit)
            .toList();

        int refreshed = 0;
        for (String ticker : due) {
            // One bad ticker must not abort the pass — the same discipline dailySnapshots uses
            // per account.
            try {
                refresh(ticker);
                refreshed++;
            } catch (Exception ex) {
                log.warn("Security profile refresh failed for {}: {}", ticker, ex.getMessage());
            }
        }
        return refreshed;
    }

    /**
     * Field-by-field merge across providers: the first non-null sector wins, and independently
     * the first non-null country. This differs from {@code resolveComposition}, which stops at
     * the first provider with any data at all — deliberately, because here Yahoo has the sector
     * and only Boursorama has the ISIN the country comes from.
     */
    private EquityProfile resolveEquity(String ticker) {
        String sector = null;
        String country = null;
        boolean domicile = false;
        List<String> sources = new ArrayList<>();

        for (EquityProfileProvider provider : equityProviders) {
            if (sector != null && country != null) break;
            if (!provider.supports(ticker)) continue;
            Optional<EquityProfile> answer;
            try {
                answer = provider.fetch(ticker);
            } catch (Exception ex) {
                log.debug("Equity profile provider {} failed for {}: {}",
                    provider.getClass().getSimpleName(), ticker, ex.getMessage());
                continue;
            }
            if (answer.isEmpty() || answer.get().isEmpty()) continue;
            EquityProfile p = answer.get();
            if (sector == null && p.sectorKey() != null) {
                sector = p.sectorKey();
                sources.add(p.source());
            }
            if (country == null && p.countryKey() != null) {
                country = p.countryKey();
                domicile = p.countryIsDomicile();
                if (!sources.contains(p.source())) sources.add(p.source());
            }
        }
        return new EquityProfile(sector, country,
            sources.isEmpty() ? null : String.join(" · ", sources), domicile);
    }

    private static List<SecurityCompositionSlice> slicesOf(EtfComposition composition) {
        List<SecurityCompositionSlice> slices = new ArrayList<>();
        add(slices, composition.companies(), SecuritySliceKind.COMPANY);
        add(slices, composition.countries(), SecuritySliceKind.COUNTRY);
        add(slices, composition.sectors(), SecuritySliceKind.SECTOR);
        return slices;
    }

    private static void add(List<SecurityCompositionSlice> target,
                            List<WeightedSlice> source, SecuritySliceKind kind) {
        if (source == null) return;
        Set<String> seen = new HashSet<>();
        for (WeightedSlice slice : source) {
            if (slice.label() == null || slice.label().isBlank()) continue;
            // The unique key is (profile, kind, label); a provider repeating a label would
            // otherwise fail the whole save rather than the one duplicate line.
            if (!seen.add(slice.label())) continue;
            target.add(SecurityCompositionSlice.builder()
                .kind(kind)
                .label(slice.label())
                .percent(slice.percent())
                .build());
        }
    }
}
