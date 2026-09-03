package com.tcs.bancs.MS;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Elementary Transaction: MSETQueryMessageStatus
 * Read-only inquiry transaction for high-throughput non-mutating lookups.
 */
public class MSETQueryMessageStatus {

    private final MSDGMessageGrabber dataGrabber;

    public MSETQueryMessageStatus() {
        this.dataGrabber = new MSDGMessageGrabber();
    }

    public MSETQueryMessageStatus(MSDGMessageGrabber dataGrabber) {
        this.dataGrabber = dataGrabber;
    }

    /**
     * Primary Elementary Transaction fetch method: MSETQueryMessageStatusFetch
     */
    public MO_OUT_SwiftMT103 MSETQueryMessageStatusFetch(String lookupKey) {
        if (lookupKey == null || lookupKey.isBlank()) {
            lookupKey = "INQUIRY_DEFAULT";
        }

        MessageHeaderRecord entity = this.dataGrabber.fetchMessageHeaderRecordById(lookupKey);
        AuditTrailService.logAuditEvent("ELEMENTARY_TRANSACTION", "MSETQueryMessageStatus", lookupKey, "FETCH");

        MO_OUT_SwiftMT103 resp = new MO_OUT_SwiftMT103();
        resp.setMessageCorrelationId("ET_" + lookupKey);
        return resp;
    }

    public boolean isHealthy() {
        return this.dataGrabber != null;
    }
}
