package com.picsou.service;

import com.picsou.dto.ProjectionResponse;
import com.picsou.model.*;
import com.picsou.repository.GoalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class ProjectionServiceTest {

    private static final Long MEMBER = 1L;

    @Mock AccountAccessResolver accessResolver;
    @Mock AccountService accountService;
    @Mock GoalRepository goalRepository;

    @InjectMocks ProjectionService service;

    private final List<Account> accounts = new ArrayList<>();
    private final Map<Long, BigDecimal> shares = new HashMap<>();
    private final List<Goal> goals = new ArrayList<>();
    private long nextId = 1;

    @BeforeEach
    void wireDefaults() {
        lenient().when(accessResolver.readableAccounts(MEMBER)).thenReturn(accounts);
        lenient().when(accessResolver.sharesFor(any(), any())).thenReturn(shares);
        lenient().when(goalRepository.findAllByMemberIdOrderByCreatedAtAsc(MEMBER)).thenReturn(goals);
    }

    private Account account(AccountType type, String value) {
        Account account = Account.builder()
            .id(nextId++).name(type.name()).type(type).currency("EUR").color("#000")
            .currentBalance(new BigDecimal(value)).build();
        accounts.add(account);
        shares.put(account.getId(), new BigDecimal("100"));
        lenient().when(accountService.valuation(account)).thenReturn(
            new AccountService.Valuation(new BigDecimal(value), new BigDecimal(value), true, true, false));
        return account;
    }

    private void plan(String monthly, LocalDate start, LocalDate end) {
        goals.add(Goal.builder()
            .id(nextId++).name("Plan").type(GoalType.RECURRING_INVESTMENT)
            .monthlyAmount(new BigDecimal(monthly)).startDate(start).endDate(end)
            .accounts(new ArrayList<>()).build());
    }

    private static ProjectionResponse.Scenario scenario(ProjectionResponse r, String key) {
        return r.scenarios().stream().filter(s -> s.key().equals(key)).findFirst().orElseThrow();
    }

    @Test
    void theBaseIsTheInvestablePortfolioOnly() {
        account(AccountType.PEA, "50000");
        account(AccountType.CRYPTO, "10000");
        account(AccountType.LIVRET_A, "6000");
        // Property does not compound at an equity rate, and a loan amortises rather than grows.
        account(AccountType.REAL_ESTATE, "300000");
        account(AccountType.LOAN, "120000");
        account(AccountType.OTHER, "5000");

        assertThat(service.project(MEMBER, 10).baseValueEur()).isEqualByComparingTo("66000.00");
    }

    @Test
    void usesTheGeometricMonthlyRateSoTheLabelStaysTrue() {
        // 10% a year compounded monthly must reach exactly x1.10 after twelve months. Dividing
        // by twelve instead reaches 10.47%, overstating the first year and far more over twenty.
        account(AccountType.PEA, "10000");

        ProjectionResponse response = service.project(MEMBER, 1);
        List<ProjectionResponse.Point> points = scenario(response, "OPTIMISTIC").points();

        assertThat(points).hasSize(2);
        assertThat(points.get(1).valueEur().doubleValue()).isCloseTo(11000.0, within(0.5));
    }

    @Test
    void contributionsLandAtTheEndOfTheMonth() {
        // At the start of the month the very first payment would earn a month of growth it never
        // saw, and that error compounds across the whole horizon.
        account(AccountType.PEA, "0");
        plan("100", null, null);

        ProjectionResponse response = service.project(MEMBER, 1);
        List<ProjectionResponse.Point> points = scenario(response, "OPTIMISTIC").points();

        // 12 end-of-month payments of 100 with only 11 months of growth between them: strictly
        // more than 1200 (the last payment earns nothing) but well under 1200 x 1.10.
        double value = points.get(1).valueEur().doubleValue();
        assertThat(value).isGreaterThan(1200.0).isLessThan(1320.0);
        assertThat(points.get(1).contributedEur()).isEqualByComparingTo("1200.00");
    }

    @Test
    void aPlanOutsideItsWindowContributesNothing() {
        account(AccountType.PEA, "0");
        plan("100", LocalDate.now().plusYears(5), null);          // not started yet
        plan("50", null, LocalDate.now().minusMonths(1));         // already finished

        ProjectionResponse response = service.project(MEMBER, 1);

        assertThat(response.monthlyInflowEur()).isEqualByComparingTo("0.00");
        assertThat(scenario(response, "LIVRET_A").points().get(1).contributedEur())
            .isEqualByComparingTo("0.00");
    }

    @Test
    void aPlanThatEndsMidHorizonStopsContributing() {
        account(AccountType.PEA, "0");
        plan("100", null, LocalDate.now().plusMonths(6));

        ProjectionResponse response = service.project(MEMBER, 1);

        // Months 1..6 inclusive of the current month's window, then nothing.
        assertThat(scenario(response, "LIVRET_A").points().get(1).contributedEur())
            .isEqualByComparingTo("600.00");
    }

    @Test
    void savingsTargetGoalsAreNotContributions() {
        account(AccountType.PEA, "0");
        goals.add(Goal.builder()
            .id(99L).name("Trip").type(GoalType.SAVINGS_TARGET)
            .targetAmount(new BigDecimal("5000")).deadline(LocalDate.now().plusYears(1))
            .accounts(new ArrayList<>()).build());

        assertThat(service.project(MEMBER, 1).monthlyInflowEur()).isEqualByComparingTo("0.00");
    }

    @Test
    void sharesAreAppliedOnce() {
        Account joint = account(AccountType.PEA, "10000");
        shares.put(joint.getId(), new BigDecimal("50"));

        assertThat(service.project(MEMBER, 1).baseValueEur()).isEqualByComparingTo("5000.00");
    }

    @Test
    void allFourScenariosCoverTheSameHorizon() {
        account(AccountType.PEA, "10000");

        ProjectionResponse response = service.project(MEMBER, 20);

        assertThat(response.scenarios()).hasSize(4);
        assertThat(response.scenarios()).allSatisfy(s -> assertThat(s.points()).hasSize(21));
        // Ordered by assumption, so a legend rendered in payload order reads sensibly.
        assertThat(response.scenarios()).extracting(ProjectionResponse.Scenario::key)
            .containsExactly("LIVRET_A", "PESSIMISTIC", "REALISTIC", "OPTIMISTIC");
    }

    @Test
    void aHigherAssumptionAlwaysEndsHigher() {
        account(AccountType.PEA, "10000");
        plan("200", null, null);

        ProjectionResponse response = service.project(MEMBER, 20);

        BigDecimal previous = BigDecimal.ZERO;
        for (ProjectionResponse.Scenario s : response.scenarios()) {
            BigDecimal last = s.points().get(s.points().size() - 1).valueEur();
            assertThat(last).isGreaterThan(previous);
            previous = last;
        }
    }

    @Test
    void theHorizonIsClamped() {
        account(AccountType.PEA, "1000");

        assertThat(service.project(MEMBER, 0).years()).isEqualTo(1);
        assertThat(service.project(MEMBER, 500).years()).isEqualTo(40);
    }

    @Test
    void anEmptyPortfolioWithNoPlansStaysFlatAtZero() {
        ProjectionResponse response = service.project(MEMBER, 10);

        assertThat(response.baseValueEur()).isEqualByComparingTo("0.00");
        assertThat(scenario(response, "OPTIMISTIC").points())
            .allSatisfy(p -> assertThat(p.valueEur()).isEqualByComparingTo("0.00"));
    }

    @Test
    void aPortfolioWithNoPlansStillCompounds() {
        account(AccountType.PEA, "10000");

        ProjectionResponse response = service.project(MEMBER, 10);
        List<ProjectionResponse.Point> points = scenario(response, "REALISTIC").points();

        assertThat(points.get(points.size() - 1).valueEur()).isGreaterThan(new BigDecimal("20000"));
        // Contributions never moved, so the whole gain is return.
        assertThat(points.get(points.size() - 1).contributedEur()).isEqualByComparingTo("10000.00");
    }
}
