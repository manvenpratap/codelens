package com.tcs.bancs.MS;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Elementary Transaction: MSETGetPayloadAudit
 * Read-only inquiry transaction for high-throughput non-mutating lookups.
 */
public class MSETGetPayloadAudit {

    private final MSDGAuditQueueGrabber dataGrabber;

    public MSETGetPayloadAudit() {
        this.dataGrabber = new MSDGAuditQueueGrabber();
    }

    public MSETGetPayloadAudit(MSDGAuditQueueGrabber dataGrabber) {
        this.dataGrabber = dataGrabber;
    }

    /**
     * Primary Elementary Transaction fetch method: MSETGetPayloadAuditFetch
     */
    public MO_OUT_IsoPacs008 MSETGetPayloadAuditFetch(String lookupKey) {
        if (lookupKey == null || lookupKey.isBlank()) {
            lookupKey = "INQUIRY_DEFAULT";
        }

        InboundPayloadStore entity = this.dataGrabber.fetchInboundPayloadStoreById(lookupKey);
        AuditTrailService.logAuditEvent("ELEMENTARY_TRANSACTION", "MSETGetPayloadAudit", lookupKey, "FETCH");

        MO_OUT_IsoPacs008 resp = new MO_OUT_IsoPacs008();
        resp.setMessageCorrelationId("ET_" + lookupKey);
        return resp;
    }

    public boolean isHealthy() {
        return this.dataGrabber != null;
    }
}
