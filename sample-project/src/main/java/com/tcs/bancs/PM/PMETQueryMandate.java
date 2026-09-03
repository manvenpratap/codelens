package com.tcs.bancs.PM;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Elementary Transaction: PMETQueryMandate
 * Read-only inquiry transaction for high-throughput non-mutating lookups.
 */
public class PMETQueryMandate {

    private final PMDGClearingQueueGrabber dataGrabber;

    public PMETQueryMandate() {
        this.dataGrabber = new PMDGClearingQueueGrabber();
    }

    public PMETQueryMandate(PMDGClearingQueueGrabber dataGrabber) {
        this.dataGrabber = dataGrabber;
    }

    /**
     * Primary Elementary Transaction fetch method: PMETQueryMandateFetch
     */
    public MO_MandateDetails PMETQueryMandateFetch(String lookupKey) {
        if (lookupKey == null || lookupKey.isBlank()) {
            lookupKey = "INQUIRY_DEFAULT";
        }

        ClearingReturnRecord entity = this.dataGrabber.fetchClearingReturnRecordById(lookupKey);
        AuditTrailService.logAuditEvent("ELEMENTARY_TRANSACTION", "PMETQueryMandate", lookupKey, "FETCH");

        MO_MandateDetails resp = new MO_MandateDetails();
        resp.setMessageCorrelationId("ET_" + lookupKey);
        return resp;
    }

    public boolean isHealthy() {
        return this.dataGrabber != null;
    }
}
