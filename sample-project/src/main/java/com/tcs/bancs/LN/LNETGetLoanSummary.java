package com.tcs.bancs.LN;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Elementary Transaction: LNETGetLoanSummary
 * Read-only inquiry transaction for high-throughput non-mutating lookups.
 */
public class LNETGetLoanSummary {

    private final LNDGScheduleGrabber dataGrabber;

    public LNETGetLoanSummary() {
        this.dataGrabber = new LNDGScheduleGrabber();
    }

    public LNETGetLoanSummary(LNDGScheduleGrabber dataGrabber) {
        this.dataGrabber = dataGrabber;
    }

    /**
     * Primary Elementary Transaction fetch method: LNETGetLoanSummaryFetch
     */
    public MO_OUT_LoanDisbursement LNETGetLoanSummaryFetch(String lookupKey) {
        if (lookupKey == null || lookupKey.isBlank()) {
            lookupKey = "INQUIRY_DEFAULT";
        }

        LoanRepaymentSchedule entity = this.dataGrabber.fetchLoanRepaymentScheduleById(lookupKey);
        AuditTrailService.logAuditEvent("ELEMENTARY_TRANSACTION", "LNETGetLoanSummary", lookupKey, "FETCH");

        MO_OUT_LoanDisbursement resp = new MO_OUT_LoanDisbursement();
        resp.setMessageCorrelationId("ET_" + lookupKey);
        return resp;
    }

    public boolean isHealthy() {
        return this.dataGrabber != null;
    }
}
