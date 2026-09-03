package com.tcs.bancs.CL;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Elementary Transaction: CLETCheckSettlementStatus
 * Read-only inquiry transaction for high-throughput non-mutating lookups.
 */
public class CLETCheckSettlementStatus {

    private final CLDGSettlementGrabber dataGrabber;

    public CLETCheckSettlementStatus() {
        this.dataGrabber = new CLDGSettlementGrabber();
    }

    public CLETCheckSettlementStatus(CLDGSettlementGrabber dataGrabber) {
        this.dataGrabber = dataGrabber;
    }

    /**
     * Primary Elementary Transaction fetch method: CLETCheckSettlementStatusFetch
     */
    public MO_OUT_SettlementInstruct CLETCheckSettlementStatusFetch(String lookupKey) {
        if (lookupKey == null || lookupKey.isBlank()) {
            lookupKey = "INQUIRY_DEFAULT";
        }

        SettlementInstruction entity = this.dataGrabber.fetchSettlementInstructionById(lookupKey);
        AuditTrailService.logAuditEvent("ELEMENTARY_TRANSACTION", "CLETCheckSettlementStatus", lookupKey, "FETCH");

        MO_OUT_SettlementInstruct resp = new MO_OUT_SettlementInstruct();
        resp.setMessageCorrelationId("ET_" + lookupKey);
        return resp;
    }

    public boolean isHealthy() {
        return this.dataGrabber != null;
    }
}
