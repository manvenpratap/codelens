package com.tcs.bancs.MS;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Elementary Transaction: MSETInspectQueueHealth
 * Read-only inquiry transaction for high-throughput non-mutating lookups.
 */
public class MSETInspectQueueHealth {

    private final MSDGPayloadGrabber dataGrabber;

    public MSETInspectQueueHealth() {
        this.dataGrabber = new MSDGPayloadGrabber();
    }

    public MSETInspectQueueHealth(MSDGPayloadGrabber dataGrabber) {
        this.dataGrabber = dataGrabber;
    }

    /**
     * Primary Elementary Transaction fetch method: MSETInspectQueueHealthFetch
     */
    public MO_OUT_FixExecutionReport MSETInspectQueueHealthFetch(String lookupKey) {
        if (lookupKey == null || lookupKey.isBlank()) {
            lookupKey = "INQUIRY_DEFAULT";
        }

        OutboundDispatchQueue entity = this.dataGrabber.fetchOutboundDispatchQueueById(lookupKey);
        AuditTrailService.logAuditEvent("ELEMENTARY_TRANSACTION", "MSETInspectQueueHealth", lookupKey, "FETCH");

        MO_OUT_FixExecutionReport resp = new MO_OUT_FixExecutionReport();
        resp.setMessageCorrelationId("ET_" + lookupKey);
        return resp;
    }

    public boolean isHealthy() {
        return this.dataGrabber != null;
    }
}
