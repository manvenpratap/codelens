package com.tcs.bancs.AM;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Elementary Transaction: AMETQueryAccountDetails
 * Read-only inquiry transaction for high-throughput non-mutating lookups.
 */
public class AMETQueryAccountDetails {

    private final AMDGBalanceGrabber dataGrabber;

    public AMETQueryAccountDetails() {
        this.dataGrabber = new AMDGBalanceGrabber();
    }

    public AMETQueryAccountDetails(AMDGBalanceGrabber dataGrabber) {
        this.dataGrabber = dataGrabber;
    }

    /**
     * Primary Elementary Transaction fetch method: AMETQueryAccountDetailsFetch
     */
    public MO_OUT_FundTransfer AMETQueryAccountDetailsFetch(String lookupKey) {
        if (lookupKey == null || lookupKey.isBlank()) {
            lookupKey = "INQUIRY_DEFAULT";
        }

        AccountLimit entity = this.dataGrabber.fetchAccountLimitById(lookupKey);
        AuditTrailService.logAuditEvent("ELEMENTARY_TRANSACTION", "AMETQueryAccountDetails", lookupKey, "FETCH");

        MO_OUT_FundTransfer resp = new MO_OUT_FundTransfer();
        resp.setMessageCorrelationId("ET_" + lookupKey);
        return resp;
    }

    public boolean isHealthy() {
        return this.dataGrabber != null;
    }
}
