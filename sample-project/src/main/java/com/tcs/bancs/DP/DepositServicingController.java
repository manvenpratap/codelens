package com.tcs.bancs.DP;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Inbound Channel Controller: DepositServicingController
 * Dispatches inbound requests from REST, Branch, ISO, and FIX channels.
 */
public class DepositServicingController {

    private final DPBTLiquidatePrematurely businessTransaction;
    private final DPETSimulateMaturityValue elementaryTransaction;

    public DepositServicingController() {
        this.businessTransaction = new DPBTLiquidatePrematurely();
        this.elementaryTransaction = new DPETSimulateMaturityValue();
    }

    public DepositServicingController(DPBTLiquidatePrematurely bt, DPETSimulateMaturityValue et) {
        this.businessTransaction = bt;
        this.elementaryTransaction = et;
    }

    /**
     * Inbound mutating command handler.
     */
    public MO_OUT_PrematureWithdrawal handleExecuteRequest(MO_INP_PrematureWithdrawal request) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "DepositServicingController", request.getMessageCorrelationId(), "MUTATION");
        return this.businessTransaction.DPBTLiquidatePrematurelyExecute(request);
    }

    /**
     * Inbound read-only query handler.
     */
    public MO_OUT_PrematureWithdrawal handleInquiryRequest(String queryKey) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "DepositServicingController", queryKey, "INQUIRY");
        return this.elementaryTransaction.DPETSimulateMaturityValueFetch(queryKey);
    }

    public boolean ping() {
        return true;
    }
}
