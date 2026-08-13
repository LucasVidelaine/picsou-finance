package com.picsou.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * What a security is, cached durably so a portfolio-wide breakdown never has to ask the network.
 *
 * <p>Global rather than member-scoped: a sector is not private data. The read path reads only
 * this table; {@code SchedulerService} refreshes it weekly, and a ticker nobody has warmed yet
 * simply reports as unclassified rather than blocking a page render on a scrape.
 */
@Entity
@Table(
    name = "security_profile",
    uniqueConstraints = @UniqueConstraint(name = "uk_security_profile_ticker", columnNames = "ticker")
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SecurityProfile extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    private String ticker;

    /** ETF / STOCK / CRYPTO / UNKNOWN, as {@code SecurityInsightService} classifies it. */
    @Column(name = "asset_type", nullable = false, length = 20)
    private String assetType;

    /** A single share's sector; an ETF carries its breakdown in {@link #slices} instead. */
    @Column(name = "sector_key", length = 40)
    private String sectorKey;

    @Column(name = "country_key", length = 2)
    private String countryKey;

    @Column(length = 60)
    private String source;

    /** The provider's own "portfolio as of" date, when it publishes one. */
    @Column(name = "as_of")
    private LocalDate asOf;

    /** When we last asked. Drives the weekly refresh, and is not the same as {@link #asOf}. */
    @Column(name = "refreshed_at", nullable = false)
    private Instant refreshedAt;

    @OneToMany(mappedBy = "profile", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<SecurityCompositionSlice> slices = new ArrayList<>();

    /** Replaces the whole breakdown; orphanRemoval deletes whatever the provider dropped. */
    public void replaceSlices(List<SecurityCompositionSlice> replacements) {
        slices.clear();
        replacements.forEach(s -> s.setProfile(this));
        slices.addAll(replacements);
    }
}
