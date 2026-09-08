package com.tcs.bancs.MS;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Inbound Channel Controller: FixChannelController
 * Dispatches inbound requests to primary service transaction methods.
 */
public class FixChannelController {

    private final SwiftParserService service;

    public FixChannelController() {
        this.service = new SwiftParserService();
    }

    public FixChannelController(SwiftParserService service) {
        this.service = service;
    }

    /**
     * Inbound Business Transaction dispatch method: MSBTTransformPayload
     */
    public MO_OUT_FixExecutionReport MSBTTransformPayload(MO_INP_FixNewOrderSingle request) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "FixChannelController", request != null ? request.getMessageCorrelationId() : "", "MUTATION");
        return this.service.MSBTTransformPayload(request);
    }

    /**
     * Inbound Elementary Transaction dispatch method: MSETInspectQueueHealth
     */
    public MO_OUT_FixExecutionReport MSETInspectQueueHealth(String queryKey) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "FixChannelController", queryKey, "INQUIRY");
        return this.service.MSETInspectQueueHealth(queryKey);
    }

    /**
     * Generic execute request handler delegating to MSBTTransformPayload.
     */
    public MO_OUT_FixExecutionReport handleExecuteRequest(MO_INP_FixNewOrderSingle request) {
        return this.MSBTTransformPayload(request);
    }

    /**
     * Generic inquiry request handler delegating to MSETInspectQueueHealth.
     */
    public MO_OUT_FixExecutionReport handleInquiryRequest(String queryKey) {
        return this.MSETInspectQueueHealth(queryKey);
    }

    public boolean ping() {
        return true;
    }
}
