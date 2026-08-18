package com.picsou.export.xlsx;

import com.picsou.dto.AccountResponse;
import com.picsou.dto.HoldingResponse;
import com.picsou.model.AccountType;
import com.picsou.model.Debt;
import com.picsou.model.PropertyValuation;
import com.picsou.repository.DebtRepository;
import com.picsou.repository.PropertyValuationRepository;
import com.picsou.service.AccountService;
import com.picsou.service.LoanAmortizationService;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.WorkbookUtil;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStream;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.picsou.export.xlsx.LabelKey.*;

/**
 * Builds the "one sheet per account" spreadsheet: a summary sheet, then each selected account's
 * identity, positions and property / loan detail.
 *
 * <p>Distinct from {@code DataExportService}, which answers the GDPR question — everything about
 * one user, flattened to CSV and JSON. This answers "let me analyse these accounts in a
 * spreadsheet", so the shape is per-account and the figures are typed cells.
 *
 * <p>Every read goes through the member-scoped path
 * ({@link AccountService#findById(Long, Long)} raises for an account the member may not read),
 * so an id from outside the caller's perimeter never reaches a sheet.
 */
@Service
public class AccountsWorkbookService {

    private static final Logger log = LoggerFactory.getLogger(AccountsWorkbookService.class);

    /** Rows kept in memory per sheet; the rest spill to a temp file. */
    private static final int ROW_ACCESS_WINDOW = 200;

    /** Excel's own cap. {@code createSafeSheetName} enforces it; the dedup suffix must respect it too. */
    private static final int MAX_SHEET_NAME = 31;

    private final AccountService accountService;
    private final PropertyValuationRepository valuationRepository;
    private final DebtRepository debtRepository;
    private final LoanAmortizationService loanAmortizationService;

    public AccountsWorkbookService(AccountService accountService,
                                   PropertyValuationRepository valuationRepository,
                                   DebtRepository debtRepository,
                                   LoanAmortizationService loanAmortizationService) {
        this.accountService = accountService;
        this.valuationRepository = valuationRepository;
        this.debtRepository = debtRepository;
        this.loanAmortizationService = loanAmortizationService;
    }

    /**
     * Streams the workbook for {@code accountIds} into {@code out}.
     *
     * <p>Read-only, but transactional so the lazy associations behind the loan and property
     * lookups resolve on one connection instead of one per account.
     */
    @Transactional(readOnly = true)
    public void export(List<Long> accountIds, Long memberId, SheetLabels labels, OutputStream out)
        throws IOException {

        List<AccountExportData> data = accountIds.stream()
            .distinct()
            .map(id -> gather(id, memberId))
            .toList();

        SXSSFWorkbook wb = new SXSSFWorkbook(ROW_ACCESS_WINDOW);
        try {
            WorkbookStyles styles = new WorkbookStyles(wb);
            writeSummarySheet(wb, styles, labels, data);

            AccountSheetWriter writer = new AccountSheetWriter(labels, styles);
            Set<String> used = new HashSet<>();
            for (AccountExportData d : data) {
                Sheet sheet = wb.createSheet(uniqueSheetName(d.account(), labels, used));
                writer.write(sheet, d);
            }
            wb.write(out);
        } finally {
            // SXSSF spills rows to temp files; without this they outlive the request.
            wb.dispose();
            wb.close();
        }
    }

    private AccountExportData gather(Long accountId, Long memberId) {
        AccountResponse account = accountService.findById(accountId, memberId);

        List<HoldingResponse> holdings = accountService.getHoldings(accountId, memberId);

        List<PropertyValuation> valuations = account.type() == AccountType.REAL_ESTATE
            ? valuationRepository.findByAccountIdOrderByValuedAtDesc(accountId)
            : List.of();

        LoanAmortizationService.LoanScheduleResponse schedule = null;
        if (account.type() == AccountType.LOAN) {
            Debt debt = debtRepository.findByAccountId(accountId).orElse(null);
            // A LOAN account can exist with no Debt row behind it (typed in, never detailed);
            // the sheet then shows whatever DebtResponse carried and no schedule.
            if (debt != null) {
                schedule = loanAmortizationService.compute(debt);
            } else {
                log.debug("accounts_export.loan_without_debt accountId={}", accountId);
            }
        }

        return new AccountExportData(account, holdings, valuations, schedule);
    }

    private void writeSummarySheet(SXSSFWorkbook wb, WorkbookStyles styles, SheetLabels labels,
                                   List<AccountExportData> data) {
        Sheet sheet = wb.createSheet(safeName(labels.get(SUMMARY_SHEET), "Summary"));
        sheet.setColumnWidth(0, 34 * 256);
        for (int i = 1; i <= 7; i++) {
            sheet.setColumnWidth(i, 18 * 256);
        }

        SheetCursor cursor = new SheetCursor(sheet, styles);
        cursor.field(labels.get(EXPORTED_AT), Instant.now());
        cursor.blank();
        cursor.headerRow(List.of(
            labels.get(ACCOUNT_NAME), labels.get(ACCOUNT_TYPE), labels.get(PROVIDER),
            labels.get(CURRENCY), labels.get(BALANCE), labels.get(BALANCE_EUR),
            labels.get(SHARE_PERCENT), labels.get(LAST_SYNCED_AT)
        ));
        for (AccountExportData d : data) {
            AccountResponse a = d.account();
            SheetCursor.RowCursor row = cursor.row();
            row.text(a.name());
            row.text(a.type() == null ? null : a.type().name());
            row.text(a.provider());
            row.text(a.currency());
            row.money(a.currentBalance());
            row.money(a.currentBalanceEur());
            row.percent(a.sharePercent());
            row.dateTime(a.lastSyncedAt());
        }
    }

    /**
     * A sheet name Excel will accept, unique within the workbook.
     *
     * <p>Two accounts genuinely called "Livret A" is the common case, not an edge one, and a
     * duplicate name makes POI throw rather than degrade — so the suffix is mandatory, and it
     * has to eat into the 31-character budget rather than push past it.
     */
    private String uniqueSheetName(AccountResponse account, SheetLabels labels, Set<String> used) {
        String fallback = labels.get(ACCOUNT_FALLBACK_NAME) + " " + account.id();
        String base = safeName(account.name(), fallback);

        String candidate = base;
        int suffix = 2;
        while (!used.add(candidate.toLowerCase(Locale.ROOT))) {
            String tail = " (" + suffix++ + ")";
            int keep = Math.min(base.length(), MAX_SHEET_NAME - tail.length());
            candidate = base.substring(0, keep) + tail;
        }
        return candidate;
    }

    /** Replaces the characters Excel forbids and trims to 31 chars, falling back if nothing is left. */
    private String safeName(String raw, String fallback) {
        String candidate = raw == null ? "" : raw.trim();
        if (candidate.isEmpty()) candidate = fallback;
        String safe = WorkbookUtil.createSafeSheetName(candidate).trim();
        return safe.isEmpty() ? WorkbookUtil.createSafeSheetName(fallback) : safe;
    }
}
