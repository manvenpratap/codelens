package com.tcs.bancs.PM;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Inbound Channel Controller: PaymentGatewayController
 * Dispatches inbound requests to primary service transaction methods.
 */
public class PaymentGatewayController {

    private final PaymentInitiationService service;

    public PaymentGatewayController() {
        this.service = new PaymentInitiationService();
    }

    public PaymentGatewayController(PaymentInitiationService service) {
        this.service = service;
    }

    /**
     * Inbound Business Transaction dispatch method: PMBTInitiatePayment
     */
    public MO_OUT_PaymentInitiation PMBTInitiatePayment(MO_INP_PaymentInitiation request) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "PaymentGatewayController", request != null ? request.getMessageCorrelationId() : "", "MUTATION");
        return this.service.PMBTInitiatePayment(request);
    }

    /**
     * Inbound Elementary Transaction dispatch method: PMETGetPaymentStatus
     */
    public MO_OUT_PaymentInitiation PMETGetPaymentStatus(String queryKey) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "PaymentGatewayController", queryKey, "INQUIRY");
        return this.service.PMETGetPaymentStatus(queryKey);
    }

    /**
     * Generic execute request handler delegating to PMBTInitiatePayment.
     */
    public MO_OUT_PaymentInitiation handleExecuteRequest(MO_INP_PaymentInitiation request) {
        return this.PMBTInitiatePayment(request);
    }

    /**
     * Generic inquiry request handler delegating to PMETGetPaymentStatus.
     */
    public MO_OUT_PaymentInitiation handleInquiryRequest(String queryKey) {
        return this.PMETGetPaymentStatus(queryKey);
    }

    public boolean ping() {
        return true;
    }
}
