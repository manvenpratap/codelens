package com.tcs.bancs.MS;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Inbound Channel Controller: SwiftGatewayController
 * Dispatches inbound requests from REST, Branch, ISO, and FIX channels.
 */
public class SwiftGatewayController {

    private final MSBTDispatchOutboundMessage businessTransaction;
    private final MSETGetPayloadAudit elementaryTransaction;

    public SwiftGatewayController() {
        this.businessTransaction = new MSBTDispatchOutboundMessage();
        this.elementaryTransaction = new MSETGetPayloadAudit();
    }

    public SwiftGatewayController(MSBTDispatchOutboundMessage bt, MSETGetPayloadAudit et) {
        this.businessTransaction = bt;
        this.elementaryTransaction = et;
    }

    /**
     * Inbound mutating command handler.
     */
    public MO_OUT_IsoPacs008 handleExecuteRequest(MO_INP_IsoPacs008 request) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "SwiftGatewayController", request.getMessageCorrelationId(), "MUTATION");
        return this.businessTransaction.MSBTDispatchOutboundMessageExecute(request);
    }

    /**
     * Inbound read-only query handler.
     */
    public MO_OUT_IsoPacs008 handleInquiryRequest(String queryKey) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "SwiftGatewayController", queryKey, "INQUIRY");
        return this.elementaryTransaction.MSETGetPayloadAuditFetch(queryKey);
    }

    public boolean ping() {
        return true;
    }
}
