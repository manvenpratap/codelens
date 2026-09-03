package com.tcs.bancs.AM;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Elementary Transaction: AMETSearchStatements
 * Read-only inquiry transaction for high-throughput non-mutating lookups.
 */
public class AMETSearchStatements {

    private final AMDGLimitGrabber dataGrabber;

    public AMETSearchStatements() {
        this.dataGrabber = new AMDGLimitGrabber();
    }

    public AMETSearchStatements(AMDGLimitGrabber dataGrabber) {
        this.dataGrabber = dataGrabber;
    }

    /**
     * Primary Elementary Transaction fetch method: AMETSearchStatementsFetch
     */
    public MO_OUT_BalanceInquiry AMETSearchStatementsFetch(String lookupKey) {
        if (lookupKey == null || lookupKey.isBlank()) {
            lookupKey = "INQUIRY_DEFAULT";
        }

        OverdraftFacility entity = this.dataGrabber.fetchOverdraftFacilityById(lookupKey);
        AuditTrailService.logAuditEvent("ELEMENTARY_TRANSACTION", "AMETSearchStatements", lookupKey, "FETCH");

        MO_OUT_BalanceInquiry resp = new MO_OUT_BalanceInquiry();
        resp.setMessageCorrelationId("ET_" + lookupKey);
        return resp;
    }

    public boolean isHealthy() {
        return this.dataGrabber != null;
    }
}
