package com.tcs.bancs.MS;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Inbound Channel Controller: FixChannelController
 * Dispatches inbound requests from REST, Branch, ISO, and FIX channels.
 */
public class FixChannelController {

    private final MSBTTransformPayload businessTransaction;
    private final MSETInspectQueueHealth elementaryTransaction;

    public FixChannelController() {
        this.businessTransaction = new MSBTTransformPayload();
        this.elementaryTransaction = new MSETInspectQueueHealth();
    }

    public FixChannelController(MSBTTransformPayload bt, MSETInspectQueueHealth et) {
        this.businessTransaction = bt;
        this.elementaryTransaction = et;
    }

    /**
     * Inbound mutating command handler.
     */
    public MO_OUT_FixExecutionReport handleExecuteRequest(MO_INP_FixNewOrderSingle request) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "FixChannelController", request.getMessageCorrelationId(), "MUTATION");
        return this.businessTransaction.MSBTTransformPayloadExecute(request);
    }

    /**
     * Inbound read-only query handler.
     */
    public MO_OUT_FixExecutionReport handleInquiryRequest(String queryKey) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "FixChannelController", queryKey, "INQUIRY");
        return this.elementaryTransaction.MSETInspectQueueHealthFetch(queryKey);
    }

    public boolean ping() {
        return true;
    }
}
