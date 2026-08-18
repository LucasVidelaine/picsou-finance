package com.picsou.export.xlsx;

import com.picsou.dto.AccountResponse;
import com.picsou.dto.HoldingResponse;
import com.picsou.model.PropertyValuation;
import com.picsou.service.LoanAmortizationService;

import java.util.List;

/**
 * Everything one sheet needs, gathered before any of it is written.
 *
 * <p>Blocks the account has nothing to say about arrive empty or null and are simply not
 * rendered: a passbook gets its header and stops there.
 *
 * @param account    member-scoped account, already carrying its property / debt metadata
 * @param holdings   positions with their live valuation, empty for an account that holds nothing
 * @param valuations property estimate history, newest first, empty for anything but a property
 * @param schedule   amortization of the loan behind this account, null unless it is a LOAN
 */
record AccountExportData(
    AccountResponse account,
    List<HoldingResponse> holdings,
    List<PropertyValuation> valuations,
    LoanAmortizationService.LoanScheduleResponse schedule
) {}
