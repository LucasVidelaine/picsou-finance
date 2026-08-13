package com.picsou.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * How the member's listed holdings spread across sectors and regions.
 *
 * <p>Covers the equity sleeve only — savings, property and crypto have no sector. ETFs are
 * looked through to their composition; a single share contributes its whole value to one sector
 * and one country.
 *
 * @param classifiedValueEur   value the breakdown could actually place
 * @param unclassifiedValueEur value it could not, reported rather than renormalised away — the
 *                             same discipline as the {@code Others} remainder in the holding
 *                             modal and {@code Valuation.anyPriced}
 * @param pendingTickers       tickers with no profile yet, so "not classified" reads as "not
 *                             looked up yet" rather than "unknowable"
 */
public record DiversificationResponse(
    BigDecimal totalValueEur,
    BigDecimal classifiedValueEur,
    BigDecimal unclassifiedValueEur,
    BigDecimal coveragePercent,
    List<String> pendingTickers,
    Breakdown sectors,
    Breakdown countries
) {

    /**
     * @param score        0-100, from the effective number of positions {@code 1/Σw²} against a
     *                     target count. Computed over the classified part only; read it next to
     *                     {@code coveragePercent}
     * @param effectiveCount the inverse Herfindahl index — how many buckets this really is,
     *                     which is what makes 40/30/30 read as 3 and 96/2/2 read as 1
     * @param slices       descending, percentages of the classified value
     * @param basis        {@code EXPOSURE} for a pure look-through, {@code MIXED} once a directly
     *                     held share contributes its domicile — the two are different quantities
     *                     and the UI has to say so
     */
    public record Breakdown(
        int score,
        BigDecimal effectiveCount,
        int targetCount,
        String basis,
        List<WeightedSlice> slices
    ) {}
}
