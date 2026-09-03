package com.tcs.bancs.LN;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Elementary Transaction: LNETCheckDelinquencyStatus
 * Read-only inquiry transaction for high-throughput non-mutating lookups.
 */
public class LNETCheckDelinquencyStatus {

    private final LNDGDisbursementGrabber dataGrabber;

    public LNETCheckDelinquencyStatus() {
        this.dataGrabber = new LNDGDisbursementGrabber();
    }

    public LNETCheckDelinquencyStatus(LNDGDisbursementGrabber dataGrabber) {
        this.dataGrabber = dataGrabber;
    }

    /**
     * Primary Elementary Transaction fetch method: LNETCheckDelinquencyStatusFetch
     */
    public MO_OUT_ScheduleRestructure LNETCheckDelinquencyStatusFetch(String lookupKey) {
        if (lookupKey == null || lookupKey.isBlank()) {
            lookupKey = "INQUIRY_DEFAULT";
        }

        DelinquencyRecord entity = this.dataGrabber.fetchDelinquencyRecordById(lookupKey);
        AuditTrailService.logAuditEvent("ELEMENTARY_TRANSACTION", "LNETCheckDelinquencyStatus", lookupKey, "FETCH");

        MO_OUT_ScheduleRestructure resp = new MO_OUT_ScheduleRestructure();
        resp.setMessageCorrelationId("ET_" + lookupKey);
        return resp;
    }

    public boolean isHealthy() {
        return this.dataGrabber != null;
    }
}
