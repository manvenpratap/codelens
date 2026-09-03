package com.tcs.bancs.AN;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Elementary Transaction: ANETGetRegulatoryFilingStatus
 * Read-only inquiry transaction for high-throughput non-mutating lookups.
 */
public class ANETGetRegulatoryFilingStatus {

    private final ANDGReportGrabber dataGrabber;

    public ANETGetRegulatoryFilingStatus() {
        this.dataGrabber = new ANDGReportGrabber();
    }

    public ANETGetRegulatoryFilingStatus(ANDGReportGrabber dataGrabber) {
        this.dataGrabber = dataGrabber;
    }

    /**
     * Primary Elementary Transaction fetch method: ANETGetRegulatoryFilingStatusFetch
     */
    public MO_OUT_LiquidityStressCheck ANETGetRegulatoryFilingStatusFetch(String lookupKey) {
        if (lookupKey == null || lookupKey.isBlank()) {
            lookupKey = "INQUIRY_DEFAULT";
        }

        RegulatoryReportSnapshot entity = this.dataGrabber.fetchRegulatoryReportSnapshotById(lookupKey);
        AuditTrailService.logAuditEvent("ELEMENTARY_TRANSACTION", "ANETGetRegulatoryFilingStatus", lookupKey, "FETCH");

        MO_OUT_LiquidityStressCheck resp = new MO_OUT_LiquidityStressCheck();
        resp.setMessageCorrelationId("ET_" + lookupKey);
        return resp;
    }

    public boolean isHealthy() {
        return this.dataGrabber != null;
    }
}
