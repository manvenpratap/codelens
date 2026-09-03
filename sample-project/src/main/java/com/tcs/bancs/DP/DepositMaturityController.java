package com.tcs.bancs.DP;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Inbound Channel Controller: DepositMaturityController
 * Dispatches inbound requests from REST, Branch, ISO, and FIX channels.
 */
public class DepositMaturityController {

    private final DPBTMatureDeposit businessTransaction;
    private final DPETCalculateBreakValue elementaryTransaction;

    public DepositMaturityController() {
        this.businessTransaction = new DPBTMatureDeposit();
        this.elementaryTransaction = new DPETCalculateBreakValue();
    }

    public DepositMaturityController(DPBTMatureDeposit bt, DPETCalculateBreakValue et) {
        this.businessTransaction = bt;
        this.elementaryTransaction = et;
    }

    /**
     * Inbound mutating command handler.
     */
    public MO_OUT_MaturityInstruction handleExecuteRequest(MO_INP_MaturityInstruction request) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "DepositMaturityController", request.getMessageCorrelationId(), "MUTATION");
        return this.businessTransaction.DPBTMatureDepositExecute(request);
    }

    /**
     * Inbound read-only query handler.
     */
    public MO_OUT_MaturityInstruction handleInquiryRequest(String queryKey) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "DepositMaturityController", queryKey, "INQUIRY");
        return this.elementaryTransaction.DPETCalculateBreakValueFetch(queryKey);
    }

    public boolean ping() {
        return true;
    }
}
