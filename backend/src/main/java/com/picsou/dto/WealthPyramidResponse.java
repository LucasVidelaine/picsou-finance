package com.picsou.dto;

import com.picsou.model.WealthTier;

import java.math.BigDecimal;
import java.util.List;

/**
 * How the member's assets sit against the investment pyramid, and how well.
 *
 * <p>Built from assets only, never from net worth. That is already the dashboard's rule
 * (assets divide by total assets, liabilities by total liabilities) and it is the right one
 * here too: counting property net of its mortgage would penalise exactly the leverage the
 * pyramid wants maximised. Debt is surfaced instead as {@code loanToValue} beside the
 * property tier.
 *
 * @param totalAssetsEur  every readable account except loans, weighted by the member's shares
 * @param allocatableEur  the base the four target percentages apply to — total assets minus the
 *                        part of the safety net that is doing its job (see {@link SafetyNet})
 * @param safetyNet       the base of the pyramid, measured against an absolute target rather
 *                        than a share
 * @param tiers           the allocation vector. Four investment tiers, plus a
 *                        {@link WealthTier#SAFETY_NET} line carrying the safety net's
 *                        <em>excess</em> — cash beyond the target is money in the wrong place,
 *                        so it belongs in the allocation with a target of 0 rather than being
 *                        quietly excluded. The four percentages always sum to 100 with it.
 */
public record WealthPyramidResponse(
    BigDecimal totalAssetsEur,
    BigDecimal allocatableEur,
    SafetyNet safetyNet,
    List<TierLine> tiers,
    Score score
) {

    /**
     * @param valueEur  what the member actually holds in cash-like accounts
     * @param targetEur {@code monthlyEssentialExpenses × safetyNetMonths}; null when unknown
     * @param coverage  {@code valueEur / targetEur}; null when unknown
     * @param excessEur {@code max(0, valueEur - targetEur)}; zero when unknown, because a target
     *                  we cannot compute must not be assumed to be exceeded
     * @param known     false when the member has never stated their monthly expenses. The tier is
     *                  then <em>unscored</em>, not scored zero — see {@link Score#global()}
     */
    public record SafetyNet(
        BigDecimal valueEur,
        BigDecimal targetEur,
        BigDecimal coverage,
        BigDecimal excessEur,
        boolean known,
        Integer score
    ) {}

    /**
     * @param valueEur      share-weighted value counted in this tier; for {@code SAFETY_NET} this
     *                      is the excess only, not the whole cushion
     * @param actualPercent this tier's share of {@code allocatableEur}
     * @param targetPercent the member's target; always 0 for {@code SAFETY_NET}
     * @param gapPercent    {@code actualPercent - targetPercent}, positive when over-weighted
     */
    public record TierLine(
        WealthTier tier,
        BigDecimal valueEur,
        BigDecimal actualPercent,
        BigDecimal targetPercent,
        BigDecimal gapPercent,
        List<TierAccount> accounts
    ) {}

    public record TierAccount(Long accountId, String name, String color, BigDecimal valueEur) {}

    /**
     * @param global         0-100. When {@link SafetyNet#known()} is false this is the allocation
     *                       score alone, adjusted by the modifiers — scoring someone zero because
     *                       they have not filled in a form would be a lie
     * @param allocation     {@code 100 × (1 - ½Σ|actual - target|)}: 100 minus the fraction of
     *                       wealth sitting in the wrong tier, expressed in points
     * @param misplacedPercent the same quantity in its plain form — the share of allocatable
     *                       wealth that would have to move to hit every target
     * @param cryptoPenalty  0-10, scaled by how much crypto actually weighs
     * @param leverageBonus  0-5, peaking between 60% and 85% LTV
     * @param cryptoTopTenShare null when the member holds no crypto
     * @param loanToValue    null when the member owns no property
     */
    public record Score(
        int global,
        int allocation,
        BigDecimal misplacedPercent,
        BigDecimal cryptoPenalty,
        BigDecimal leverageBonus,
        BigDecimal cryptoTopTenShare,
        BigDecimal loanToValue
    ) {}
}
