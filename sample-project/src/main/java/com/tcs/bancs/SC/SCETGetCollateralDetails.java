package com.tcs.bancs.SC;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Elementary Transaction: SCETGetCollateralDetails
 * Read-only inquiry transaction for high-throughput non-mutating lookups.
 */
public class SCETGetCollateralDetails {

    private final SCDGCollateralGrabber dataGrabber;

    public SCETGetCollateralDetails() {
        this.dataGrabber = new SCDGCollateralGrabber();
    }

    public SCETGetCollateralDetails(SCDGCollateralGrabber dataGrabber) {
        this.dataGrabber = dataGrabber;
    }

    /**
     * Primary Elementary Transaction fetch method: SCETGetCollateralDetailsFetch
     */
    public MO_OUT_CollateralRegistration SCETGetCollateralDetailsFetch(String lookupKey) {
        if (lookupKey == null || lookupKey.isBlank()) {
            lookupKey = "INQUIRY_DEFAULT";
        }

        CollateralItem entity = this.dataGrabber.fetchCollateralItemById(lookupKey);
        AuditTrailService.logAuditEvent("ELEMENTARY_TRANSACTION", "SCETGetCollateralDetails", lookupKey, "FETCH");

        MO_OUT_CollateralRegistration resp = new MO_OUT_CollateralRegistration();
        resp.setMessageCorrelationId("ET_" + lookupKey);
        return resp;
    }

    public boolean isHealthy() {
        return this.dataGrabber != null;
    }
}
