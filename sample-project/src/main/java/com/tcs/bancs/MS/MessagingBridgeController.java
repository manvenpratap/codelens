package com.tcs.bancs.MS;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Inbound Channel Controller: MessagingBridgeController
 * Dispatches inbound requests to primary service transaction methods.
 */
public class MessagingBridgeController {

    private final SwiftParserService service;

    public MessagingBridgeController() {
        this.service = new SwiftParserService();
    }

    public MessagingBridgeController(SwiftParserService service) {
        this.service = service;
    }

    /**
     * Inbound Business Transaction dispatch method: MSBTRouteInboundMessage
     */
    public MO_OUT_SwiftMT103 MSBTRouteInboundMessage(MO_INP_SwiftMT103 request) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "MessagingBridgeController", request != null ? request.getMessageCorrelationId() : "", "MUTATION");
        return this.service.MSBTRouteInboundMessage(request);
    }

    /**
     * Inbound Elementary Transaction dispatch method: MSETQueryMessageStatus
     */
    public MO_OUT_SwiftMT103 MSETQueryMessageStatus(String queryKey) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "MessagingBridgeController", queryKey, "INQUIRY");
        return this.service.MSETQueryMessageStatus(queryKey);
    }

    /**
     * Generic execute request handler delegating to MSBTRouteInboundMessage.
     */
    public MO_OUT_SwiftMT103 handleExecuteRequest(MO_INP_SwiftMT103 request) {
        return this.MSBTRouteInboundMessage(request);
    }

    /**
     * Generic inquiry request handler delegating to MSETQueryMessageStatus.
     */
    public MO_OUT_SwiftMT103 handleInquiryRequest(String queryKey) {
        return this.MSETQueryMessageStatus(queryKey);
    }

    public boolean ping() {
        return true;
    }
}
