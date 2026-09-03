package com.tcs.bancs.AN;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Elementary Transaction: ANETGetPnLSummary
 * Read-only inquiry transaction for high-throughput non-mutating lookups.
 */
public class ANETGetPnLSummary {

    private final ANDGAnalyticsGrabber dataGrabber;

    public ANETGetPnLSummary() {
        this.dataGrabber = new ANDGAnalyticsGrabber();
    }

    public ANETGetPnLSummary(ANDGAnalyticsGrabber dataGrabber) {
        this.dataGrabber = dataGrabber;
    }

    /**
     * Primary Elementary Transaction fetch method: ANETGetPnLSummaryFetch
     */
    public MO_OUT_PnLCalculation ANETGetPnLSummaryFetch(String lookupKey) {
        if (lookupKey == null || lookupKey.isBlank()) {
            lookupKey = "INQUIRY_DEFAULT";
        }

        PnLSummaryRecord entity = this.dataGrabber.fetchPnLSummaryRecordById(lookupKey);
        AuditTrailService.logAuditEvent("ELEMENTARY_TRANSACTION", "ANETGetPnLSummary", lookupKey, "FETCH");

        MO_OUT_PnLCalculation resp = new MO_OUT_PnLCalculation();
        resp.setMessageCorrelationId("ET_" + lookupKey);
        return resp;
    }

    public boolean isHealthy() {
        return this.dataGrabber != null;
    }
}
