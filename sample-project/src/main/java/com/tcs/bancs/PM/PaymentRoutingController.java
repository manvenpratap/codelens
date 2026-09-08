package com.tcs.bancs.PM;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Inbound Channel Controller: PaymentRoutingController
 * Dispatches inbound requests to primary service transaction methods.
 */
public class PaymentRoutingController {

    private final PaymentInitiationService service;

    public PaymentRoutingController() {
        this.service = new PaymentInitiationService();
    }

    public PaymentRoutingController(PaymentInitiationService service) {
        this.service = service;
    }

    /**
     * Inbound Business Transaction dispatch method: PMBTSettlePaymentInstruction
     */
    public MO_OUT_PaymentCancellation PMBTSettlePaymentInstruction(MO_INP_PaymentCancellation request) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "PaymentRoutingController", request != null ? request.getMessageCorrelationId() : "", "MUTATION");
        return this.service.PMBTSettlePaymentInstruction(request);
    }

    /**
     * Inbound Elementary Transaction dispatch method: PMETValidateIban
     */
    public MO_OUT_PaymentCancellation PMETValidateIban(String queryKey) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "PaymentRoutingController", queryKey, "INQUIRY");
        return this.service.PMETValidateIban(queryKey);
    }

    /**
     * Generic execute request handler delegating to PMBTSettlePaymentInstruction.
     */
    public MO_OUT_PaymentCancellation handleExecuteRequest(MO_INP_PaymentCancellation request) {
        return this.PMBTSettlePaymentInstruction(request);
    }

    /**
     * Generic inquiry request handler delegating to PMETValidateIban.
     */
    public MO_OUT_PaymentCancellation handleInquiryRequest(String queryKey) {
        return this.PMETValidateIban(queryKey);
    }

    public boolean ping() {
        return true;
    }
}
