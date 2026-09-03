package com.tcs.bancs.PM;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Elementary Transaction: PMETCheckRoutingPath
 * Read-only inquiry transaction for high-throughput non-mutating lookups.
 */
public class PMETCheckRoutingPath {

    private final PMDGRoutingGrabber dataGrabber;

    public PMETCheckRoutingPath() {
        this.dataGrabber = new PMDGRoutingGrabber();
    }

    public PMETCheckRoutingPath(PMDGRoutingGrabber dataGrabber) {
        this.dataGrabber = dataGrabber;
    }

    /**
     * Primary Elementary Transaction fetch method: PMETCheckRoutingPathFetch
     */
    public MO_OUT_PaymentStatusQuery PMETCheckRoutingPathFetch(String lookupKey) {
        if (lookupKey == null || lookupKey.isBlank()) {
            lookupKey = "INQUIRY_DEFAULT";
        }

        RoutingDirectory entity = this.dataGrabber.fetchRoutingDirectoryById(lookupKey);
        AuditTrailService.logAuditEvent("ELEMENTARY_TRANSACTION", "PMETCheckRoutingPath", lookupKey, "FETCH");

        MO_OUT_PaymentStatusQuery resp = new MO_OUT_PaymentStatusQuery();
        resp.setMessageCorrelationId("ET_" + lookupKey);
        return resp;
    }

    public boolean isHealthy() {
        return this.dataGrabber != null;
    }
}
