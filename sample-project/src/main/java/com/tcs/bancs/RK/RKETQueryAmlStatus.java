package com.tcs.bancs.RK;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Elementary Transaction: RKETQueryAmlStatus
 * Read-only inquiry transaction for high-throughput non-mutating lookups.
 */
public class RKETQueryAmlStatus {

    private final RKDGAmlAlertGrabber dataGrabber;

    public RKETQueryAmlStatus() {
        this.dataGrabber = new RKDGAmlAlertGrabber();
    }

    public RKETQueryAmlStatus(RKDGAmlAlertGrabber dataGrabber) {
        this.dataGrabber = dataGrabber;
    }

    /**
     * Primary Elementary Transaction fetch method: RKETQueryAmlStatusFetch
     */
    public MO_OUT_ExposureRecalculate RKETQueryAmlStatusFetch(String lookupKey) {
        if (lookupKey == null || lookupKey.isBlank()) {
            lookupKey = "INQUIRY_DEFAULT";
        }

        AmlAlertRecord entity = this.dataGrabber.fetchAmlAlertRecordById(lookupKey);
        AuditTrailService.logAuditEvent("ELEMENTARY_TRANSACTION", "RKETQueryAmlStatus", lookupKey, "FETCH");

        MO_OUT_ExposureRecalculate resp = new MO_OUT_ExposureRecalculate();
        resp.setMessageCorrelationId("ET_" + lookupKey);
        return resp;
    }

    public boolean isHealthy() {
        return this.dataGrabber != null;
    }
}
