package com.tcs.bancs.GL;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Elementary Transaction: GLETValidateDoubleEntry
 * Read-only inquiry transaction for high-throughput non-mutating lookups.
 */
public class GLETValidateDoubleEntry {

    private final GLDGPeriodGrabber dataGrabber;

    public GLETValidateDoubleEntry() {
        this.dataGrabber = new GLDGPeriodGrabber();
    }

    public GLETValidateDoubleEntry(GLDGPeriodGrabber dataGrabber) {
        this.dataGrabber = dataGrabber;
    }

    /**
     * Primary Elementary Transaction fetch method: GLETValidateDoubleEntryFetch
     */
    public MO_TrialBalanceItem GLETValidateDoubleEntryFetch(String lookupKey) {
        if (lookupKey == null || lookupKey.isBlank()) {
            lookupKey = "INQUIRY_DEFAULT";
        }

        FinancialPeriod entity = this.dataGrabber.fetchFinancialPeriodById(lookupKey);
        AuditTrailService.logAuditEvent("ELEMENTARY_TRANSACTION", "GLETValidateDoubleEntry", lookupKey, "FETCH");

        MO_TrialBalanceItem resp = new MO_TrialBalanceItem();
        resp.setMessageCorrelationId("ET_" + lookupKey);
        return resp;
    }

    public boolean isHealthy() {
        return this.dataGrabber != null;
    }
}
