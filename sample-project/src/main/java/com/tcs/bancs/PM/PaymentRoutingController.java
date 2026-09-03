package com.tcs.bancs.PM;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Inbound Channel Controller: PaymentRoutingController
 * Dispatches inbound requests from REST, Branch, ISO, and FIX channels.
 */
public class PaymentRoutingController {

    private final PMBTSettlePaymentInstruction businessTransaction;
    private final PMETValidateIban elementaryTransaction;

    public PaymentRoutingController() {
        this.businessTransaction = new PMBTSettlePaymentInstruction();
        this.elementaryTransaction = new PMETValidateIban();
    }

    public PaymentRoutingController(PMBTSettlePaymentInstruction bt, PMETValidateIban et) {
        this.businessTransaction = bt;
        this.elementaryTransaction = et;
    }

    /**
     * Inbound mutating command handler.
     */
    public MO_OUT_PaymentCancellation handleExecuteRequest(MO_INP_PaymentCancellation request) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "PaymentRoutingController", request.getMessageCorrelationId(), "MUTATION");
        return this.businessTransaction.PMBTSettlePaymentInstructionExecute(request);
    }

    /**
     * Inbound read-only query handler.
     */
    public MO_OUT_PaymentCancellation handleInquiryRequest(String queryKey) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "PaymentRoutingController", queryKey, "INQUIRY");
        return this.elementaryTransaction.PMETValidateIbanFetch(queryKey);
    }

    public boolean ping() {
        return true;
    }
}
