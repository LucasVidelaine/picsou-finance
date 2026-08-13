package com.picsou.controller;

import com.picsou.dto.AllocationTargetsRequest;
import com.picsou.dto.AllocationTargetsResponse;
import com.picsou.dto.DiversificationResponse;
import com.picsou.dto.EssentialExpenseEstimateResponse;
import com.picsou.dto.WealthPyramidResponse;
import com.picsou.service.AllocationTargetService;
import com.picsou.service.EssentialExpenseEstimator;
import com.picsou.service.PortfolioDiversificationService;
import com.picsou.service.UserContext;
import com.picsou.service.WealthPyramidService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/**
 * Wealth analysis: how the portfolio is built, rather than what it is worth.
 *
 * <p>Singular path, following {@code /api/dashboard}: this is one view of the member's wealth,
 * not a collection of analyses.
 */
@RestController
@RequestMapping("/api/analysis")
public class AnalysisController {

    private final WealthPyramidService pyramidService;
    private final PortfolioDiversificationService diversificationService;
    private final AllocationTargetService allocationTargetService;
    private final EssentialExpenseEstimator expenseEstimator;
    private final UserContext userContext;

    public AnalysisController(WealthPyramidService pyramidService,
                              PortfolioDiversificationService diversificationService,
                              AllocationTargetService allocationTargetService,
                              EssentialExpenseEstimator expenseEstimator,
                              UserContext userContext) {
        this.pyramidService = pyramidService;
        this.diversificationService = diversificationService;
        this.allocationTargetService = allocationTargetService;
        this.expenseEstimator = expenseEstimator;
        this.userContext = userContext;
    }

    /** The five tiers, their weights against the member's targets, and the resulting score. */
    @GetMapping("/pyramid")
    public WealthPyramidResponse pyramid() {
        return pyramidService.pyramid(userContext.currentMemberId());
    }

    /**
     * How the equity sleeve spreads across sectors and regions.
     *
     * <p>Reads persisted profiles only, so it never blocks on a scrape. Whatever has no profile
     * yet is reported as unclassified with its tickers named, rather than renormalised away.
     */
    @GetMapping("/diversification")
    public DiversificationResponse diversification() {
        return diversificationService.diversification(userContext.currentMemberId());
    }

    /** The member's targets, or the shipped defaults when they have never set any. */
    @GetMapping("/allocation-targets")
    public AllocationTargetsResponse targets() {
        return allocationTargetService.get(userContext.currentMemberId());
    }

    /** Replaces the whole profile. 422 when the four percentages do not sum to 100. */
    @PutMapping("/allocation-targets")
    public AllocationTargetsResponse replaceTargets(@Valid @RequestBody AllocationTargetsRequest request) {
        return allocationTargetService.replace(userContext.currentMemberId(), request);
    }

    /**
     * What the member's own transactions suggest they spend monthly. Offered as a suggestion for
     * the form above — accepting it is a PUT, so the figure is never stored on their behalf.
     */
    @GetMapping("/essential-expenses/estimate")
    public EssentialExpenseEstimateResponse expenseEstimate() {
        return expenseEstimator.estimate(userContext.currentMemberId());
    }
}
