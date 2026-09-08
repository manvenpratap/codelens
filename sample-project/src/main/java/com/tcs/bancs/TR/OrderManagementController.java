package com.tcs.bancs.TR;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Inbound Channel Controller: OrderManagementController
 * Dispatches inbound requests to primary service transaction methods.
 */
public class OrderManagementController {

    private final OrderRoutingService service;

    public OrderManagementController() {
        this.service = new OrderRoutingService();
    }

    public OrderManagementController(OrderRoutingService service) {
        this.service = service;
    }

    /**
     * Inbound Business Transaction dispatch method: TRBTCancelOrder
     */
    public MO_OUT_OrderCancel TRBTCancelOrder(MO_INP_OrderCancel request) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "OrderManagementController", request != null ? request.getMessageCorrelationId() : "", "MUTATION");
        return this.service.TRBTCancelOrder(request);
    }

    /**
     * Inbound Elementary Transaction dispatch method: TRETQueryActiveOrders
     */
    public MO_OUT_OrderCancel TRETQueryActiveOrders(String queryKey) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "OrderManagementController", queryKey, "INQUIRY");
        return this.service.TRETQueryActiveOrders(queryKey);
    }

    /**
     * Generic execute request handler delegating to TRBTCancelOrder.
     */
    public MO_OUT_OrderCancel handleExecuteRequest(MO_INP_OrderCancel request) {
        return this.TRBTCancelOrder(request);
    }

    /**
     * Generic inquiry request handler delegating to TRETQueryActiveOrders.
     */
    public MO_OUT_OrderCancel handleInquiryRequest(String queryKey) {
        return this.TRETQueryActiveOrders(queryKey);
    }

    public boolean ping() {
        return true;
    }
}
