package com.tcs.bancs.DP;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Elementary Transaction: DPETCalculateBreakValue
 * Read-only inquiry transaction for high-throughput non-mutating lookups.
 */
public class DPETCalculateBreakValue {

    private final DPDGPenaltyRuleGrabber dataGrabber;

    public DPETCalculateBreakValue() {
        this.dataGrabber = new DPDGPenaltyRuleGrabber();
    }

    public DPETCalculateBreakValue(DPDGPenaltyRuleGrabber dataGrabber) {
        this.dataGrabber = dataGrabber;
    }

    /**
     * Primary Elementary Transaction fetch method: DPETCalculateBreakValueFetch
     */
    public MO_OUT_MaturityInstruction DPETCalculateBreakValueFetch(String lookupKey) {
        if (lookupKey == null || lookupKey.isBlank()) {
            lookupKey = "INQUIRY_DEFAULT";
        }

        PrematurePenaltyRule entity = this.dataGrabber.fetchPrematurePenaltyRuleById(lookupKey);
        AuditTrailService.logAuditEvent("ELEMENTARY_TRANSACTION", "DPETCalculateBreakValue", lookupKey, "FETCH");

        MO_OUT_MaturityInstruction resp = new MO_OUT_MaturityInstruction();
        resp.setMessageCorrelationId("ET_" + lookupKey);
        return resp;
    }

    public boolean isHealthy() {
        return this.dataGrabber != null;
    }
}
