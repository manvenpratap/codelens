package com.tcs.bancs.TR;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Elementary Transaction: TRETGetOrderStatus
 * Read-only inquiry transaction for high-throughput non-mutating lookups.
 */
public class TRETGetOrderStatus {

    private final TRDGTradeGrabber dataGrabber;

    public TRETGetOrderStatus() {
        this.dataGrabber = new TRDGTradeGrabber();
    }

    public TRETGetOrderStatus(TRDGTradeGrabber dataGrabber) {
        this.dataGrabber = dataGrabber;
    }

    /**
     * Primary Elementary Transaction fetch method: TRETGetOrderStatusFetch
     */
    public MO_OUT_OrderSubmission TRETGetOrderStatusFetch(String lookupKey) {
        if (lookupKey == null || lookupKey.isBlank()) {
            lookupKey = "INQUIRY_DEFAULT";
        }

        OrderEntity entity = this.dataGrabber.fetchOrderEntityById(lookupKey);
        AuditTrailService.logAuditEvent("ELEMENTARY_TRANSACTION", "TRETGetOrderStatus", lookupKey, "FETCH");

        MO_OUT_OrderSubmission resp = new MO_OUT_OrderSubmission();
        resp.setMessageCorrelationId("ET_" + lookupKey);
        return resp;
    }

    public boolean isHealthy() {
        return this.dataGrabber != null;
    }
}
