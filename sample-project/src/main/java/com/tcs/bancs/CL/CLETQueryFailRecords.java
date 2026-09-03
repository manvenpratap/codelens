package com.tcs.bancs.CL;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Elementary Transaction: CLETQueryFailRecords
 * Read-only inquiry transaction for high-throughput non-mutating lookups.
 */
public class CLETQueryFailRecords {

    private final CLDGFailGrabber dataGrabber;

    public CLETQueryFailRecords() {
        this.dataGrabber = new CLDGFailGrabber();
    }

    public CLETQueryFailRecords(CLDGFailGrabber dataGrabber) {
        this.dataGrabber = dataGrabber;
    }

    /**
     * Primary Elementary Transaction fetch method: CLETQueryFailRecordsFetch
     */
    public MO_OUT_DepositoryTransfer CLETQueryFailRecordsFetch(String lookupKey) {
        if (lookupKey == null || lookupKey.isBlank()) {
            lookupKey = "INQUIRY_DEFAULT";
        }

        SettlementFailRecord entity = this.dataGrabber.fetchSettlementFailRecordById(lookupKey);
        AuditTrailService.logAuditEvent("ELEMENTARY_TRANSACTION", "CLETQueryFailRecords", lookupKey, "FETCH");

        MO_OUT_DepositoryTransfer resp = new MO_OUT_DepositoryTransfer();
        resp.setMessageCorrelationId("ET_" + lookupKey);
        return resp;
    }

    public boolean isHealthy() {
        return this.dataGrabber != null;
    }
}
