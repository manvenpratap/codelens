package com.tcs.bancs.TR;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Inbound Channel Controller: TradingDeskController
 * Dispatches inbound requests to primary service transaction methods.
 */
public class TradingDeskController {

    private final OrderRoutingService service;

    public TradingDeskController() {
        this.service = new OrderRoutingService();
    }

    public TradingDeskController(OrderRoutingService service) {
        this.service = service;
    }

    /**
     * Inbound Business Transaction dispatch method: TRBTSubmitOrder
     */
    public MO_OUT_OrderSubmission TRBTSubmitOrder(MO_INP_OrderSubmission request) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "TradingDeskController", request != null ? request.getMessageCorrelationId() : "", "MUTATION");
        return this.service.TRBTSubmitOrder(request);
    }

    /**
     * Inbound Elementary Transaction dispatch method: TRETGetOrderStatus
     */
    public MO_OUT_OrderSubmission TRETGetOrderStatus(String queryKey) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "TradingDeskController", queryKey, "INQUIRY");
        return this.service.TRETGetOrderStatus(queryKey);
    }

    /**
     * Generic execute request handler delegating to TRBTSubmitOrder.
     */
    public MO_OUT_OrderSubmission handleExecuteRequest(MO_INP_OrderSubmission request) {
        return this.TRBTSubmitOrder(request);
    }

    /**
     * Generic inquiry request handler delegating to TRETGetOrderStatus.
     */
    public MO_OUT_OrderSubmission handleInquiryRequest(String queryKey) {
        return this.TRETGetOrderStatus(queryKey);
    }

    public boolean ping() {
        return true;
    }
}
