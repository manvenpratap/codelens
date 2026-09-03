package com.tcs.bancs.SC;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Elementary Transaction: SCETCalculateLTV
 * Read-only inquiry transaction for high-throughput non-mutating lookups.
 */
public class SCETCalculateLTV {

    private final SCDGPledgeGrabber dataGrabber;

    public SCETCalculateLTV() {
        this.dataGrabber = new SCDGPledgeGrabber();
    }

    public SCETCalculateLTV(SCDGPledgeGrabber dataGrabber) {
        this.dataGrabber = dataGrabber;
    }

    /**
     * Primary Elementary Transaction fetch method: SCETCalculateLTVFetch
     */
    public MO_OUT_CollateralRevaluation SCETCalculateLTVFetch(String lookupKey) {
        if (lookupKey == null || lookupKey.isBlank()) {
            lookupKey = "INQUIRY_DEFAULT";
        }

        CollateralPledge entity = this.dataGrabber.fetchCollateralPledgeById(lookupKey);
        AuditTrailService.logAuditEvent("ELEMENTARY_TRANSACTION", "SCETCalculateLTV", lookupKey, "FETCH");

        MO_OUT_CollateralRevaluation resp = new MO_OUT_CollateralRevaluation();
        resp.setMessageCorrelationId("ET_" + lookupKey);
        return resp;
    }

    public boolean isHealthy() {
        return this.dataGrabber != null;
    }
}
