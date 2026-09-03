package com.tcs.bancs.AN;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Elementary Transaction: ANETFetchYieldCurve
 * Read-only inquiry transaction for high-throughput non-mutating lookups.
 */
public class ANETFetchYieldCurve {

    private final ANDGYieldCurveGrabber dataGrabber;

    public ANETFetchYieldCurve() {
        this.dataGrabber = new ANDGYieldCurveGrabber();
    }

    public ANETFetchYieldCurve(ANDGYieldCurveGrabber dataGrabber) {
        this.dataGrabber = dataGrabber;
    }

    /**
     * Primary Elementary Transaction fetch method: ANETFetchYieldCurveFetch
     */
    public MO_OUT_YieldCurveQuery ANETFetchYieldCurveFetch(String lookupKey) {
        if (lookupKey == null || lookupKey.isBlank()) {
            lookupKey = "INQUIRY_DEFAULT";
        }

        YieldCurveSnapshot entity = this.dataGrabber.fetchYieldCurveSnapshotById(lookupKey);
        AuditTrailService.logAuditEvent("ELEMENTARY_TRANSACTION", "ANETFetchYieldCurve", lookupKey, "FETCH");

        MO_OUT_YieldCurveQuery resp = new MO_OUT_YieldCurveQuery();
        resp.setMessageCorrelationId("ET_" + lookupKey);
        return resp;
    }

    public boolean isHealthy() {
        return this.dataGrabber != null;
    }
}
