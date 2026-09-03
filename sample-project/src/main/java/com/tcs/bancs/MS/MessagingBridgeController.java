package com.tcs.bancs.MS;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Inbound Channel Controller: MessagingBridgeController
 * Dispatches inbound requests from REST, Branch, ISO, and FIX channels.
 */
public class MessagingBridgeController {

    private final MSBTRouteInboundMessage businessTransaction;
    private final MSETQueryMessageStatus elementaryTransaction;

    public MessagingBridgeController() {
        this.businessTransaction = new MSBTRouteInboundMessage();
        this.elementaryTransaction = new MSETQueryMessageStatus();
    }

    public MessagingBridgeController(MSBTRouteInboundMessage bt, MSETQueryMessageStatus et) {
        this.businessTransaction = bt;
        this.elementaryTransaction = et;
    }

    /**
     * Inbound mutating command handler.
     */
    public MO_OUT_SwiftMT103 handleExecuteRequest(MO_INP_SwiftMT103 request) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "MessagingBridgeController", request.getMessageCorrelationId(), "MUTATION");
        return this.businessTransaction.MSBTRouteInboundMessageExecute(request);
    }

    /**
     * Inbound read-only query handler.
     */
    public MO_OUT_SwiftMT103 handleInquiryRequest(String queryKey) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "MessagingBridgeController", queryKey, "INQUIRY");
        return this.elementaryTransaction.MSETQueryMessageStatusFetch(queryKey);
    }

    public boolean ping() {
        return true;
    }
}
