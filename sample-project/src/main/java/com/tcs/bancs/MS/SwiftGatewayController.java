package com.tcs.bancs.MS;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Inbound Channel Controller: SwiftGatewayController
 * Dispatches inbound requests to primary service transaction methods.
 */
public class SwiftGatewayController {

    private final SwiftParserService service;

    public SwiftGatewayController() {
        this.service = new SwiftParserService();
    }

    public SwiftGatewayController(SwiftParserService service) {
        this.service = service;
    }

    /**
     * Inbound Business Transaction dispatch method: MSBTDispatchOutboundMessage
     */
    public MO_OUT_IsoPacs008 MSBTDispatchOutboundMessage(MO_INP_IsoPacs008 request) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "SwiftGatewayController", request != null ? request.getMessageCorrelationId() : "", "MUTATION");
        return this.service.MSBTDispatchOutboundMessage(request);
    }

    /**
     * Inbound Elementary Transaction dispatch method: MSETGetPayloadAudit
     */
    public MO_OUT_IsoPacs008 MSETGetPayloadAudit(String queryKey) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "SwiftGatewayController", queryKey, "INQUIRY");
        return this.service.MSETGetPayloadAudit(queryKey);
    }

    /**
     * Generic execute request handler delegating to MSBTDispatchOutboundMessage.
     */
    public MO_OUT_IsoPacs008 handleExecuteRequest(MO_INP_IsoPacs008 request) {
        return this.MSBTDispatchOutboundMessage(request);
    }

    /**
     * Generic inquiry request handler delegating to MSETGetPayloadAudit.
     */
    public MO_OUT_IsoPacs008 handleInquiryRequest(String queryKey) {
        return this.MSETGetPayloadAudit(queryKey);
    }

    public boolean ping() {
        return true;
    }
}
