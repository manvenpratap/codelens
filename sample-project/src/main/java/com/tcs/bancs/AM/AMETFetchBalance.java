package com.tcs.bancs.AM;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Elementary Transaction: AMETFetchBalance
 * Read-only inquiry transaction for high-throughput non-mutating lookups.
 */
public class AMETFetchBalance {

    private final AMDGAccountGrabber dataGrabber;

    public AMETFetchBalance() {
        this.dataGrabber = new AMDGAccountGrabber();
    }

    public AMETFetchBalance(AMDGAccountGrabber dataGrabber) {
        this.dataGrabber = dataGrabber;
    }

    /**
     * Primary Elementary Transaction fetch method: AMETFetchBalanceFetch
     */
    public MO_OUT_AccountOpen AMETFetchBalanceFetch(String lookupKey) {
        if (lookupKey == null || lookupKey.isBlank()) {
            lookupKey = "INQUIRY_DEFAULT";
        }

        Account entity = this.dataGrabber.fetchAccountById(lookupKey);
        AuditTrailService.logAuditEvent("ELEMENTARY_TRANSACTION", "AMETFetchBalance", lookupKey, "FETCH");

        MO_OUT_AccountOpen resp = new MO_OUT_AccountOpen();
        resp.setMessageCorrelationId("ET_" + lookupKey);
        return resp;
    }

    public boolean isHealthy() {
        return this.dataGrabber != null;
    }
}
