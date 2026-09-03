package com.tcs.bancs.DP;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Inbound Channel Controller: DepositProductController
 * Dispatches inbound requests from REST, Branch, ISO, and FIX channels.
 */
public class DepositProductController {

    private final DPBTBookDeposit businessTransaction;
    private final DPETGetDepositDetails elementaryTransaction;

    public DepositProductController() {
        this.businessTransaction = new DPBTBookDeposit();
        this.elementaryTransaction = new DPETGetDepositDetails();
    }

    public DepositProductController(DPBTBookDeposit bt, DPETGetDepositDetails et) {
        this.businessTransaction = bt;
        this.elementaryTransaction = et;
    }

    /**
     * Inbound mutating command handler.
     */
    public MO_OUT_DepositBooking handleExecuteRequest(MO_INP_DepositBooking request) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "DepositProductController", request.getMessageCorrelationId(), "MUTATION");
        return this.businessTransaction.DPBTBookDepositExecute(request);
    }

    /**
     * Inbound read-only query handler.
     */
    public MO_OUT_DepositBooking handleInquiryRequest(String queryKey) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "DepositProductController", queryKey, "INQUIRY");
        return this.elementaryTransaction.DPETGetDepositDetailsFetch(queryKey);
    }

    public boolean ping() {
        return true;
    }
}
