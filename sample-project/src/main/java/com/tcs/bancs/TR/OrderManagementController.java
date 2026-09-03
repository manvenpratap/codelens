package com.tcs.bancs.TR;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Inbound Channel Controller: OrderManagementController
 * Dispatches inbound requests from REST, Branch, ISO, and FIX channels.
 */
public class OrderManagementController {

    private final TRBTCancelOrder businessTransaction;
    private final TRETQueryActiveOrders elementaryTransaction;

    public OrderManagementController() {
        this.businessTransaction = new TRBTCancelOrder();
        this.elementaryTransaction = new TRETQueryActiveOrders();
    }

    public OrderManagementController(TRBTCancelOrder bt, TRETQueryActiveOrders et) {
        this.businessTransaction = bt;
        this.elementaryTransaction = et;
    }

    /**
     * Inbound mutating command handler.
     */
    public MO_OUT_OrderCancel handleExecuteRequest(MO_INP_OrderCancel request) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "OrderManagementController", request.getMessageCorrelationId(), "MUTATION");
        return this.businessTransaction.TRBTCancelOrderExecute(request);
    }

    /**
     * Inbound read-only query handler.
     */
    public MO_OUT_OrderCancel handleInquiryRequest(String queryKey) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "OrderManagementController", queryKey, "INQUIRY");
        return this.elementaryTransaction.TRETQueryActiveOrdersFetch(queryKey);
    }

    public boolean ping() {
        return true;
    }
}
