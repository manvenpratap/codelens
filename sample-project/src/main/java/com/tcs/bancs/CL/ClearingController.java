package com.tcs.bancs.CL;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Inbound Channel Controller: ClearingController
 * Dispatches inbound requests from REST, Branch, ISO, and FIX channels.
 */
public class ClearingController {

    private final CLBTSettleInstruction businessTransaction;
    private final CLETCheckSettlementStatus elementaryTransaction;

    public ClearingController() {
        this.businessTransaction = new CLBTSettleInstruction();
        this.elementaryTransaction = new CLETCheckSettlementStatus();
    }

    public ClearingController(CLBTSettleInstruction bt, CLETCheckSettlementStatus et) {
        this.businessTransaction = bt;
        this.elementaryTransaction = et;
    }

    /**
     * Inbound mutating command handler.
     */
    public MO_OUT_SettlementInstruct handleExecuteRequest(MO_INP_SettlementInstruct request) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "ClearingController", request.getMessageCorrelationId(), "MUTATION");
        return this.businessTransaction.CLBTSettleInstructionExecute(request);
    }

    /**
     * Inbound read-only query handler.
     */
    public MO_OUT_SettlementInstruct handleInquiryRequest(String queryKey) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "ClearingController", queryKey, "INQUIRY");
        return this.elementaryTransaction.CLETCheckSettlementStatusFetch(queryKey);
    }

    public boolean ping() {
        return true;
    }
}
