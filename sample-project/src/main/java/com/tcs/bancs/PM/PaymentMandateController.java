package com.tcs.bancs.PM;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Inbound Channel Controller: PaymentMandateController
 * Dispatches inbound requests from REST, Branch, ISO, and FIX channels.
 */
public class PaymentMandateController {

    private final PMBTAuthorizePayment businessTransaction;
    private final PMETCheckRoutingPath elementaryTransaction;

    public PaymentMandateController() {
        this.businessTransaction = new PMBTAuthorizePayment();
        this.elementaryTransaction = new PMETCheckRoutingPath();
    }

    public PaymentMandateController(PMBTAuthorizePayment bt, PMETCheckRoutingPath et) {
        this.businessTransaction = bt;
        this.elementaryTransaction = et;
    }

    /**
     * Inbound mutating command handler.
     */
    public MO_OUT_PaymentStatusQuery handleExecuteRequest(MO_INP_PaymentStatusQuery request) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "PaymentMandateController", request.getMessageCorrelationId(), "MUTATION");
        return this.businessTransaction.PMBTAuthorizePaymentExecute(request);
    }

    /**
     * Inbound read-only query handler.
     */
    public MO_OUT_PaymentStatusQuery handleInquiryRequest(String queryKey) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "PaymentMandateController", queryKey, "INQUIRY");
        return this.elementaryTransaction.PMETCheckRoutingPathFetch(queryKey);
    }

    public boolean ping() {
        return true;
    }
}
