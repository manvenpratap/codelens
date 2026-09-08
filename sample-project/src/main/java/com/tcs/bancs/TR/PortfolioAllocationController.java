package com.tcs.bancs.TR;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Inbound Channel Controller: PortfolioAllocationController
 * Dispatches inbound requests to primary service transaction methods.
 */
public class PortfolioAllocationController {

    private final OrderRoutingService service;

    public PortfolioAllocationController() {
        this.service = new OrderRoutingService();
    }

    public PortfolioAllocationController(OrderRoutingService service) {
        this.service = service;
    }

    /**
     * Inbound Business Transaction dispatch method: TRBTExecuteTrade
     */
    public MO_OUT_TradeAllocation TRBTExecuteTrade(MO_INP_TradeAllocation request) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "PortfolioAllocationController", request != null ? request.getMessageCorrelationId() : "", "MUTATION");
        return this.service.TRBTExecuteTrade(request);
    }

    /**
     * Inbound Elementary Transaction dispatch method: TRETGetPositionSummary
     */
    public MO_OUT_TradeAllocation TRETGetPositionSummary(String queryKey) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "PortfolioAllocationController", queryKey, "INQUIRY");
        return this.service.TRETGetPositionSummary(queryKey);
    }

    /**
     * Generic execute request handler delegating to TRBTExecuteTrade.
     */
    public MO_OUT_TradeAllocation handleExecuteRequest(MO_INP_TradeAllocation request) {
        return this.TRBTExecuteTrade(request);
    }

    /**
     * Generic inquiry request handler delegating to TRETGetPositionSummary.
     */
    public MO_OUT_TradeAllocation handleInquiryRequest(String queryKey) {
        return this.TRETGetPositionSummary(queryKey);
    }

    public boolean ping() {
        return true;
    }
}
