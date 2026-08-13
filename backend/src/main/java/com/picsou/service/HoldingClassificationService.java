package com.picsou.service;

import com.picsou.dto.HoldingClassificationRequest;
import com.picsou.model.Account;
import com.picsou.model.HoldingClassification;
import com.picsou.repository.HoldingClassificationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Optional;

/**
 * Stores the member's own verdict on what a holding is.
 *
 * <p>The write is guarded by the <em>account</em> the ticker was reached through — that is what
 * ties an override to something the member actually holds — but the row itself is keyed on
 * {@code (member, ticker)}, so one correction covers the same security wherever it sits and
 * survives the account being resynced or deleted.
 */
@Service
@Transactional(readOnly = true)
public class HoldingClassificationService {

    private final HoldingClassificationRepository repository;
    private final AccountAccessResolver accessResolver;

    public HoldingClassificationService(HoldingClassificationRepository repository,
                                        AccountAccessResolver accessResolver) {
        this.repository = repository;
        this.accessResolver = accessResolver;
    }

    @Transactional
    public HoldingClassification classify(Long accountId, Long memberId, String ticker,
                                          HoldingClassificationRequest request) {
        // requireOwner, not requireReadable: reclassifying is a write, and a co-owner must not
        // rewrite how someone else's holdings are counted.
        Account account = accessResolver.requireOwner(accountId, memberId);
        String upper = ticker.toUpperCase(Locale.ROOT);

        Optional<HoldingClassification> existing =
            repository.findByMemberIdAndTicker(memberId, upper);

        if (request.wealthTier() == null && request.sectorKey() == null && request.countryKey() == null) {
            // Every field cleared means "stop overriding this ticker" — drop the row rather than
            // leaving an empty one that reads as a deliberate blank classification.
            existing.ifPresent(repository::delete);
            return null;
        }

        HoldingClassification classification = existing.orElseGet(() ->
            HoldingClassification.builder()
                .member(account.getMember())
                .ticker(upper)
                .build());

        classification.setWealthTier(request.wealthTier());
        classification.setSectorKey(request.sectorKey());
        classification.setCountryKey(request.countryKey());
        return repository.save(classification);
    }

    public Optional<HoldingClassification> find(Long memberId, String ticker) {
        return repository.findByMemberIdAndTicker(memberId, ticker.toUpperCase(Locale.ROOT));
    }
}
