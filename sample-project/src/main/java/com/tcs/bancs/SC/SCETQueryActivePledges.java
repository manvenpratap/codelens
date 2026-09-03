package com.tcs.bancs.SC;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Elementary Transaction: SCETQueryActivePledges
 * Read-only inquiry transaction for high-throughput non-mutating lookups.
 */
public class SCETQueryActivePledges {

    private final SCDGMarginCallGrabber dataGrabber;

    public SCETQueryActivePledges() {
        this.dataGrabber = new SCDGMarginCallGrabber();
    }

    public SCETQueryActivePledges(SCDGMarginCallGrabber dataGrabber) {
        this.dataGrabber = dataGrabber;
    }

    /**
     * Primary Elementary Transaction fetch method: SCETQueryActivePledgesFetch
     */
    public MO_OUT_PledgeCreation SCETQueryActivePledgesFetch(String lookupKey) {
        if (lookupKey == null || lookupKey.isBlank()) {
            lookupKey = "INQUIRY_DEFAULT";
        }

        MarginCallEvent entity = this.dataGrabber.fetchMarginCallEventById(lookupKey);
        AuditTrailService.logAuditEvent("ELEMENTARY_TRANSACTION", "SCETQueryActivePledges", lookupKey, "FETCH");

        MO_OUT_PledgeCreation resp = new MO_OUT_PledgeCreation();
        resp.setMessageCorrelationId("ET_" + lookupKey);
        return resp;
    }

    public boolean isHealthy() {
        return this.dataGrabber != null;
    }
}
