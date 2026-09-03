package com.tcs.bancs.RK;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Elementary Transaction: RKETCheckCounterpartyLimit
 * Read-only inquiry transaction for high-throughput non-mutating lookups.
 */
public class RKETCheckCounterpartyLimit {

    private final RKDGLimitGrabber dataGrabber;

    public RKETCheckCounterpartyLimit() {
        this.dataGrabber = new RKDGLimitGrabber();
    }

    public RKETCheckCounterpartyLimit(RKDGLimitGrabber dataGrabber) {
        this.dataGrabber = dataGrabber;
    }

    /**
     * Primary Elementary Transaction fetch method: RKETCheckCounterpartyLimitFetch
     */
    public MO_OUT_AmlScreening RKETCheckCounterpartyLimitFetch(String lookupKey) {
        if (lookupKey == null || lookupKey.isBlank()) {
            lookupKey = "INQUIRY_DEFAULT";
        }

        PartyRiskLimit entity = this.dataGrabber.fetchPartyRiskLimitById(lookupKey);
        AuditTrailService.logAuditEvent("ELEMENTARY_TRANSACTION", "RKETCheckCounterpartyLimit", lookupKey, "FETCH");

        MO_OUT_AmlScreening resp = new MO_OUT_AmlScreening();
        resp.setMessageCorrelationId("ET_" + lookupKey);
        return resp;
    }

    public boolean isHealthy() {
        return this.dataGrabber != null;
    }
}
