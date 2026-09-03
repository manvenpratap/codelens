package com.tcs.bancs.GL;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Elementary Transaction: GLETFetchTrialBalance
 * Read-only inquiry transaction for high-throughput non-mutating lookups.
 */
public class GLETFetchTrialBalance {

    private final GLDGVoucherGrabber dataGrabber;

    public GLETFetchTrialBalance() {
        this.dataGrabber = new GLDGVoucherGrabber();
    }

    public GLETFetchTrialBalance(GLDGVoucherGrabber dataGrabber) {
        this.dataGrabber = dataGrabber;
    }

    /**
     * Primary Elementary Transaction fetch method: GLETFetchTrialBalanceFetch
     */
    public MO_OUT_TrialBalanceQuery GLETFetchTrialBalanceFetch(String lookupKey) {
        if (lookupKey == null || lookupKey.isBlank()) {
            lookupKey = "INQUIRY_DEFAULT";
        }

        JournalVoucher entity = this.dataGrabber.fetchJournalVoucherById(lookupKey);
        AuditTrailService.logAuditEvent("ELEMENTARY_TRANSACTION", "GLETFetchTrialBalance", lookupKey, "FETCH");

        MO_OUT_TrialBalanceQuery resp = new MO_OUT_TrialBalanceQuery();
        resp.setMessageCorrelationId("ET_" + lookupKey);
        return resp;
    }

    public boolean isHealthy() {
        return this.dataGrabber != null;
    }
}
