package com.tcs.bancs.RK;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Elementary Transaction: RKETCalculateVaR
 * Read-only inquiry transaction for high-throughput non-mutating lookups.
 */
public class RKETCalculateVaR {

    private final RKDGRiskGrabber dataGrabber;

    public RKETCalculateVaR() {
        this.dataGrabber = new RKDGRiskGrabber();
    }

    public RKETCalculateVaR(RKDGRiskGrabber dataGrabber) {
        this.dataGrabber = dataGrabber;
    }

    /**
     * Primary Elementary Transaction fetch method: RKETCalculateVaRFetch
     */
    public MO_OUT_LimitEvaluation RKETCalculateVaRFetch(String lookupKey) {
        if (lookupKey == null || lookupKey.isBlank()) {
            lookupKey = "INQUIRY_DEFAULT";
        }

        RiskExposure entity = this.dataGrabber.fetchRiskExposureById(lookupKey);
        AuditTrailService.logAuditEvent("ELEMENTARY_TRANSACTION", "RKETCalculateVaR", lookupKey, "FETCH");

        MO_OUT_LimitEvaluation resp = new MO_OUT_LimitEvaluation();
        resp.setMessageCorrelationId("ET_" + lookupKey);
        return resp;
    }

    public boolean isHealthy() {
        return this.dataGrabber != null;
    }
}
