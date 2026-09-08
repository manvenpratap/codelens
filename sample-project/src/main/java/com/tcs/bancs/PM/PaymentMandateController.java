package com.tcs.bancs.PM;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Inbound Channel Controller: PaymentMandateController
 * Dispatches inbound requests to primary service transaction methods.
 */
public class PaymentMandateController {

    private final PaymentInitiationService service;

    public PaymentMandateController() {
        this.service = new PaymentInitiationService();
    }

    public PaymentMandateController(PaymentInitiationService service) {
        this.service = service;
    }

    /**
     * Inbound Business Transaction dispatch method: PMBTAuthorizePayment
     */
    public MO_OUT_PaymentStatusQuery PMBTAuthorizePayment(MO_INP_PaymentStatusQuery request) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "PaymentMandateController", request != null ? request.getMessageCorrelationId() : "", "MUTATION");
        return this.service.PMBTAuthorizePayment(request);
    }

    /**
     * Inbound Elementary Transaction dispatch method: PMETCheckRoutingPath
     */
    public MO_OUT_PaymentStatusQuery PMETCheckRoutingPath(String queryKey) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "PaymentMandateController", queryKey, "INQUIRY");
        return this.service.PMETCheckRoutingPath(queryKey);
    }

    /**
     * Generic execute request handler delegating to PMBTAuthorizePayment.
     */
    public MO_OUT_PaymentStatusQuery handleExecuteRequest(MO_INP_PaymentStatusQuery request) {
        return this.PMBTAuthorizePayment(request);
    }

    /**
     * Generic inquiry request handler delegating to PMETCheckRoutingPath.
     */
    public MO_OUT_PaymentStatusQuery handleInquiryRequest(String queryKey) {
        return this.PMETCheckRoutingPath(queryKey);
    }

    public boolean ping() {
        return true;
    }
}
