package com.picsou.service;

import com.picsou.dto.ProjectionResponse;
import com.picsou.model.Account;
import com.picsou.model.Goal;
import com.picsou.model.GoalType;
import com.picsou.model.WealthTier;
import com.picsou.repository.GoalRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Projects the investable portfolio forward from the member's recurring investment plans.
 *
 * <p>Deliberately not part of {@code GoalService}: that class is about progress against targets,
 * is already 450+ lines, and has no business knowing what the portfolio is worth.
 */
@Service
@Transactional(readOnly = true)
public class ProjectionService {

    /**
     * The four assumptions, defined here rather than client-side so a labelled line can never
     * disagree with the rate that produced it.
     */
    private static final List<Assumption> ASSUMPTIONS = List.of(
        new Assumption("LIVRET_A", new BigDecimal("2.0")),
        new Assumption("PESSIMISTIC", new BigDecimal("5.0")),
        new Assumption("REALISTIC", new BigDecimal("7.5")),
        new Assumption("OPTIMISTIC", new BigDecimal("10.0"))
    );

    /**
     * What compounds. Property is excluded because it does not grow at an equity rate, and loans
     * because amortisation is a different model entirely; alternatives (gold, watches, art) have
     * no return assumption worth defending either.
     */
    private static final Set<WealthTier> INVESTABLE =
        EnumSet.of(WealthTier.EQUITY, WealthTier.CRYPTO, WealthTier.SAFETY_NET);

    private static final int MIN_YEARS = 1;
    private static final int MAX_YEARS = 40;
    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);

    private record Assumption(String key, BigDecimal annualPercent) {}

    private final AccountAccessResolver accessResolver;
    private final AccountService accountService;
    private final GoalRepository goalRepository;

    public ProjectionService(AccountAccessResolver accessResolver,
                             AccountService accountService,
                             GoalRepository goalRepository) {
        this.accessResolver = accessResolver;
        this.accountService = accountService;
        this.goalRepository = goalRepository;
    }

    public ProjectionResponse project(Long memberId, int requestedYears) {
        int years = Math.max(MIN_YEARS, Math.min(MAX_YEARS, requestedYears));

        List<Account> accounts = accessResolver.readableAccounts(memberId);
        Map<Long, BigDecimal> shares = accessResolver.sharesFor(accounts, memberId);

        BigDecimal running = BigDecimal.ZERO;
        for (Account account : accounts) {
            if (!INVESTABLE.contains(WealthTier.of(account.getType()))) continue;
            running = running.add(AccountAccessResolver.weigh(
                accountService.valuation(account).liveEur(), shares.get(account.getId())));
        }
        final BigDecimal base = running;

        List<Goal> plans = goalRepository.findAllByMemberIdOrderByCreatedAtAsc(memberId).stream()
            .filter(g -> g.getType() == GoalType.RECURRING_INVESTMENT)
            .filter(g -> g.getMonthlyAmount() != null)
            .toList();

        YearMonth start = YearMonth.now();
        BigDecimal inflowNow = contributionFor(plans, start);

        List<ProjectionResponse.Scenario> scenarios = ASSUMPTIONS.stream()
            .map(a -> new ProjectionResponse.Scenario(
                a.key(), a.annualPercent(), series(base, plans, start, years, a.annualPercent())))
            .toList();

        return new ProjectionResponse(
            base.setScale(2, RoundingMode.HALF_UP),
            inflowNow.setScale(2, RoundingMode.HALF_UP),
            years,
            scenarios);
    }

    /**
     * One monthly point per month, plus the starting point.
     *
     * <p>Contributions land at the <strong>end</strong> of the month: crediting them at the start
     * multiplies the very first payment by a month of growth it never earned, and that error
     * compounds across the whole horizon.
     */
    private List<ProjectionResponse.Point> series(BigDecimal base, List<Goal> plans,
                                                  YearMonth start, int years, BigDecimal annualPercent) {
        BigDecimal monthlyRate = monthlyRate(annualPercent);
        BigDecimal value = base;
        BigDecimal contributed = base;

        List<ProjectionResponse.Point> points = new ArrayList<>();
        points.add(point(start, value, contributed));

        for (int i = 1; i <= years * 12; i++) {
            YearMonth month = start.plusMonths(i);
            BigDecimal contribution = contributionFor(plans, month);
            value = value.multiply(BigDecimal.ONE.add(monthlyRate), MC).add(contribution);
            contributed = contributed.add(contribution);
            // Monthly maths, yearly points: 480 points per line x 4 lines would be a large
            // payload for a chart that cannot render them distinctly anyway.
            if (i % 12 == 0) points.add(point(month, value, contributed));
        }
        return points;
    }

    /**
     * The <em>geometric</em> monthly rate, {@code (1 + r)^(1/12) − 1}, never {@code r / 12}.
     *
     * <p>Dividing by twelve compounds to more than the rate on the label: 10% split that way
     * reaches 10.47% over a year, which overstates the first year by 5% of the gain and far more
     * across twenty. {@code Math.pow} on the rate is the one place a {@code double} is acceptable
     * — it is a pure ratio, not money, and every amount below stays {@code BigDecimal}.
     */
    static BigDecimal monthlyRate(BigDecimal annualPercent) {
        double annual = annualPercent.doubleValue() / 100.0;
        return BigDecimal.valueOf(Math.pow(1 + annual, 1.0 / 12.0) - 1);
    }

    /** What the plans active in {@code month} put in that month. */
    private static BigDecimal contributionFor(List<Goal> plans, YearMonth month) {
        BigDecimal total = BigDecimal.ZERO;
        for (Goal plan : plans) {
            if (plan.getStartDate() != null && month.isBefore(YearMonth.from(plan.getStartDate()))) continue;
            if (plan.getEndDate() != null && month.isAfter(YearMonth.from(plan.getEndDate()))) continue;
            total = total.add(plan.getMonthlyAmount());
        }
        return total;
    }

    private static ProjectionResponse.Point point(YearMonth month, BigDecimal value, BigDecimal contributed) {
        LocalDate date = month.atEndOfMonth();
        return new ProjectionResponse.Point(
            date,
            value.setScale(2, RoundingMode.HALF_UP),
            contributed.setScale(2, RoundingMode.HALF_UP));
    }
}
