package com.tcs.bancs.LN;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Elementary Transaction: LNETQuerySchedule
 * Read-only inquiry transaction for high-throughput non-mutating lookups.
 */
public class LNETQuerySchedule {

    private final LNDGLoanGrabber dataGrabber;

    public LNETQuerySchedule() {
        this.dataGrabber = new LNDGLoanGrabber();
    }

    public LNETQuerySchedule(LNDGLoanGrabber dataGrabber) {
        this.dataGrabber = dataGrabber;
    }

    /**
     * Primary Elementary Transaction fetch method: LNETQueryScheduleFetch
     */
    public MO_OUT_LoanApplication LNETQueryScheduleFetch(String lookupKey) {
        if (lookupKey == null || lookupKey.isBlank()) {
            lookupKey = "INQUIRY_DEFAULT";
        }

        Loan entity = this.dataGrabber.fetchLoanById(lookupKey);
        AuditTrailService.logAuditEvent("ELEMENTARY_TRANSACTION", "LNETQuerySchedule", lookupKey, "FETCH");

        MO_OUT_LoanApplication resp = new MO_OUT_LoanApplication();
        resp.setMessageCorrelationId("ET_" + lookupKey);
        return resp;
    }

    public boolean isHealthy() {
        return this.dataGrabber != null;
    }
}
