package com.picsou.dto;

import com.picsou.model.GoalType;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class GoalRequestTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    private static Set<String> paths(GoalRequest request) {
        return validator.validate(request).stream()
            .map(ConstraintViolation::getPropertyPath)
            .map(Object::toString)
            .collect(java.util.stream.Collectors.toSet());
    }

    @Test
    void anOmittedTypeMeansASavingsTarget() {
        // The compatibility guarantee: every payload written before this field existed — the
        // frontend's and four MCP tools' — omits it and must keep meaning what it meant.
        GoalRequest req = new GoalRequest("Trip", null, new BigDecimal("5000"),
            LocalDate.now().plusYears(1), null, null, null, null, List.of(1L));

        assertThat(req.type()).isEqualTo(GoalType.SAVINGS_TARGET);
        assertThat(validator.validate(req)).isEmpty();
    }

    @Test
    void aSavingsTargetNeedsBothATargetAndADeadline() {
        GoalRequest noTarget = GoalRequest.savingsTarget("Trip", null, LocalDate.now().plusYears(1), List.of(1L));
        GoalRequest noDeadline = GoalRequest.savingsTarget("Trip", new BigDecimal("5000"), null, List.of(1L));

        // Cross-field rules have no field to attach to, so the 422 keys them under the derived
        // property name. The form maps its message off that; renaming the method breaks it.
        assertThat(paths(noTarget)).contains("savingsTargetComplete");
        assertThat(paths(noDeadline)).contains("savingsTargetComplete");
    }

    @Test
    void aRecurringPlanNeedsAMonthlyAmount() {
        GoalRequest req = new GoalRequest("PEA", GoalType.RECURRING_INVESTMENT,
            null, null, null, null, null, null, List.of(1L));

        assertThat(paths(req)).contains("recurringComplete");
    }

    @Test
    void aRecurringPlanNeedsNeitherTargetNorDeadline() {
        GoalRequest req = GoalRequest.recurringInvestment(
            "PEA", new BigDecimal("300"), new BigDecimal("7.5"), null, null, 1L);

        assertThat(validator.validate(req)).isEmpty();
    }

    @Test
    void aRecurringPlanFundsExactlyOneAccount() {
        GoalRequest req = new GoalRequest("PEA", GoalType.RECURRING_INVESTMENT,
            null, null, new BigDecimal("300"), null, null, null, List.of(1L, 2L));

        assertThat(paths(req)).contains("recurringSingleAccount");
    }

    @Test
    void aSavingsTargetMaySpanSeveralAccounts() {
        GoalRequest req = GoalRequest.savingsTarget(
            "Trip", new BigDecimal("5000"), LocalDate.now().plusYears(1), List.of(1L, 2L, 3L));

        assertThat(validator.validate(req)).isEmpty();
    }

    @Test
    void theEndDateMustFollowTheStartDate() {
        GoalRequest req = GoalRequest.recurringInvestment("PEA", new BigDecimal("300"), null,
            LocalDate.now().plusYears(2), LocalDate.now().plusYears(1), 1L);

        assertThat(paths(req)).contains("dateRangeOrdered");
    }

    @Test
    void aPastDeadlineIsStillRefusedAtCreation() {
        // Dropping chk_goal_deadline removed the rule from the database, where it wrongly
        // blocked every later edit. @Future keeps it where it means what the user meant.
        GoalRequest req = GoalRequest.savingsTarget(
            "Trip", new BigDecimal("5000"), LocalDate.now().minusDays(1), List.of(1L));

        assertThat(paths(req)).contains("deadline");
    }

    @Test
    void aNameIsStillRequired() {
        GoalRequest req = GoalRequest.savingsTarget(
            "", new BigDecimal("5000"), LocalDate.now().plusYears(1), List.of(1L));

        assertThat(paths(req)).contains("name");
    }
}
