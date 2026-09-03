package com.tcs.bancs.SC;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Elementary Transaction: SCETCheckMarginDeficit
 * Read-only inquiry transaction for high-throughput non-mutating lookups.
 */
public class SCETCheckMarginDeficit {

    private final SCDGValuationGrabber dataGrabber;

    public SCETCheckMarginDeficit() {
        this.dataGrabber = new SCDGValuationGrabber();
    }

    public SCETCheckMarginDeficit(SCDGValuationGrabber dataGrabber) {
        this.dataGrabber = dataGrabber;
    }

    /**
     * Primary Elementary Transaction fetch method: SCETCheckMarginDeficitFetch
     */
    public MO_OUT_MarginCallIssue SCETCheckMarginDeficitFetch(String lookupKey) {
        if (lookupKey == null || lookupKey.isBlank()) {
            lookupKey = "INQUIRY_DEFAULT";
        }

        ValuationAppraisalReport entity = this.dataGrabber.fetchValuationAppraisalReportById(lookupKey);
        AuditTrailService.logAuditEvent("ELEMENTARY_TRANSACTION", "SCETCheckMarginDeficit", lookupKey, "FETCH");

        MO_OUT_MarginCallIssue resp = new MO_OUT_MarginCallIssue();
        resp.setMessageCorrelationId("ET_" + lookupKey);
        return resp;
    }

    public boolean isHealthy() {
        return this.dataGrabber != null;
    }
}
