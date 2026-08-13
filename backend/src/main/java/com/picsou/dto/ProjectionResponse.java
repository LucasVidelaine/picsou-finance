package com.picsou.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The investable portfolio projected forward under four return assumptions.
 *
 * <p>The base excludes property, loans and alternative assets: a house does not compound at 7.5%
 * a year, and including it would inflate every scenario by whatever the property is worth. The
 * figure is exposed so the screen can state what it is projecting from.
 *
 * @param baseValueEur      today's investable value, share-weighted
 * @param monthlyInflowEur  the sum of the recurring plans active this month, for context
 * @param scenarios         one series per assumption, all over the same horizon
 */
public record ProjectionResponse(
    BigDecimal baseValueEur,
    BigDecimal monthlyInflowEur,
    int years,
    List<Scenario> scenarios
) {

    /**
     * @param key           stable identifier the client labels ({@code LIVRET_A}, {@code PESSIMISTIC},
     *                      {@code REALISTIC}, {@code OPTIMISTIC}) — the rates are the backend's
     *                      choice, so the client must not restate them
     * @param annualPercent the assumption, so a tooltip can show it without hardcoding it
     */
    public record Scenario(
        String key,
        BigDecimal annualPercent,
        List<Point> points
    ) {}

    /**
     * @param contributedEur capital in — the base plus everything paid in since. Carried beside
     *                       the value so the chart can separate contributions from returns, the
     *                       way {@code NetWorthChart} already separates total from invested
     */
    public record Point(LocalDate date, BigDecimal valueEur, BigDecimal contributedEur) {}
}
