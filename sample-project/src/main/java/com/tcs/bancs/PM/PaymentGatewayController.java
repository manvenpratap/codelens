package com.tcs.bancs.PM;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Inbound Channel Controller: PaymentGatewayController
 * Dispatches inbound requests from REST, Branch, ISO, and FIX channels.
 */
public class PaymentGatewayController {

    private final PMBTInitiatePayment businessTransaction;
    private final PMETGetPaymentStatus elementaryTransaction;

    public PaymentGatewayController() {
        this.businessTransaction = new PMBTInitiatePayment();
        this.elementaryTransaction = new PMETGetPaymentStatus();
    }

    public PaymentGatewayController(PMBTInitiatePayment bt, PMETGetPaymentStatus et) {
        this.businessTransaction = bt;
        this.elementaryTransaction = et;
    }

    /**
     * Inbound mutating command handler.
     */
    public MO_OUT_PaymentInitiation handleExecuteRequest(MO_INP_PaymentInitiation request) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "PaymentGatewayController", request.getMessageCorrelationId(), "MUTATION");
        return this.businessTransaction.PMBTInitiatePaymentExecute(request);
    }

    /**
     * Inbound read-only query handler.
     */
    public MO_OUT_PaymentInitiation handleInquiryRequest(String queryKey) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "PaymentGatewayController", queryKey, "INQUIRY");
        return this.elementaryTransaction.PMETGetPaymentStatusFetch(queryKey);
    }

    public boolean ping() {
        return true;
    }
}
