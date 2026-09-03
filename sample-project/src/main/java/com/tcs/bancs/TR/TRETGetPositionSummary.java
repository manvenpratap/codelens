package com.tcs.bancs.TR;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Elementary Transaction: TRETGetPositionSummary
 * Read-only inquiry transaction for high-throughput non-mutating lookups.
 */
public class TRETGetPositionSummary {

    private final TRDGPortfolioGrabber dataGrabber;

    public TRETGetPositionSummary() {
        this.dataGrabber = new TRDGPortfolioGrabber();
    }

    public TRETGetPositionSummary(TRDGPortfolioGrabber dataGrabber) {
        this.dataGrabber = dataGrabber;
    }

    /**
     * Primary Elementary Transaction fetch method: TRETGetPositionSummaryFetch
     */
    public MO_OUT_TradeAllocation TRETGetPositionSummaryFetch(String lookupKey) {
        if (lookupKey == null || lookupKey.isBlank()) {
            lookupKey = "INQUIRY_DEFAULT";
        }

        PortfolioHolding entity = this.dataGrabber.fetchPortfolioHoldingById(lookupKey);
        AuditTrailService.logAuditEvent("ELEMENTARY_TRANSACTION", "TRETGetPositionSummary", lookupKey, "FETCH");

        MO_OUT_TradeAllocation resp = new MO_OUT_TradeAllocation();
        resp.setMessageCorrelationId("ET_" + lookupKey);
        return resp;
    }

    public boolean isHealthy() {
        return this.dataGrabber != null;
    }
}
