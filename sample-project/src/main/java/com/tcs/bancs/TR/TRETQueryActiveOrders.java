package com.tcs.bancs.TR;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Elementary Transaction: TRETQueryActiveOrders
 * Read-only inquiry transaction for high-throughput non-mutating lookups.
 */
public class TRETQueryActiveOrders {

    private final TRDGOrderBookGrabber dataGrabber;

    public TRETQueryActiveOrders() {
        this.dataGrabber = new TRDGOrderBookGrabber();
    }

    public TRETQueryActiveOrders(TRDGOrderBookGrabber dataGrabber) {
        this.dataGrabber = dataGrabber;
    }

    /**
     * Primary Elementary Transaction fetch method: TRETQueryActiveOrdersFetch
     */
    public MO_OUT_OrderCancel TRETQueryActiveOrdersFetch(String lookupKey) {
        if (lookupKey == null || lookupKey.isBlank()) {
            lookupKey = "INQUIRY_DEFAULT";
        }

        TradeExecution entity = this.dataGrabber.fetchTradeExecutionById(lookupKey);
        AuditTrailService.logAuditEvent("ELEMENTARY_TRANSACTION", "TRETQueryActiveOrders", lookupKey, "FETCH");

        MO_OUT_OrderCancel resp = new MO_OUT_OrderCancel();
        resp.setMessageCorrelationId("ET_" + lookupKey);
        return resp;
    }

    public boolean isHealthy() {
        return this.dataGrabber != null;
    }
}
