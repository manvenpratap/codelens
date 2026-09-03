package com.tcs.bancs.RK;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Elementary Transaction: RKETFetchExposureBreakdown
 * Read-only inquiry transaction for high-throughput non-mutating lookups.
 */
public class RKETFetchExposureBreakdown {

    private final RKDGExposureGrabber dataGrabber;

    public RKETFetchExposureBreakdown() {
        this.dataGrabber = new RKDGExposureGrabber();
    }

    public RKETFetchExposureBreakdown(RKDGExposureGrabber dataGrabber) {
        this.dataGrabber = dataGrabber;
    }

    /**
     * Primary Elementary Transaction fetch method: RKETFetchExposureBreakdownFetch
     */
    public MO_OUT_RiskOverride RKETFetchExposureBreakdownFetch(String lookupKey) {
        if (lookupKey == null || lookupKey.isBlank()) {
            lookupKey = "INQUIRY_DEFAULT";
        }

        VaRCalculationResult entity = this.dataGrabber.fetchVaRCalculationResultById(lookupKey);
        AuditTrailService.logAuditEvent("ELEMENTARY_TRANSACTION", "RKETFetchExposureBreakdown", lookupKey, "FETCH");

        MO_OUT_RiskOverride resp = new MO_OUT_RiskOverride();
        resp.setMessageCorrelationId("ET_" + lookupKey);
        return resp;
    }

    public boolean isHealthy() {
        return this.dataGrabber != null;
    }
}
