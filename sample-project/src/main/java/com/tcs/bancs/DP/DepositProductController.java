package com.tcs.bancs.DP;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Inbound Channel Controller: DepositProductController
 * Dispatches inbound requests to primary service transaction methods.
 */
public class DepositProductController {

    private final DepositBookingService service;

    public DepositProductController() {
        this.service = new DepositBookingService();
    }

    public DepositProductController(DepositBookingService service) {
        this.service = service;
    }

    /**
     * Inbound Business Transaction dispatch method: DPBTBookDeposit
     */
    public MO_OUT_DepositBooking DPBTBookDeposit(MO_INP_DepositBooking request) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "DepositProductController", request != null ? request.getMessageCorrelationId() : "", "MUTATION");
        return this.service.DPBTBookDeposit(request);
    }

    /**
     * Inbound Elementary Transaction dispatch method: DPETGetDepositDetails
     */
    public MO_OUT_DepositBooking DPETGetDepositDetails(String queryKey) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "DepositProductController", queryKey, "INQUIRY");
        return this.service.DPETGetDepositDetails(queryKey);
    }

    /**
     * Generic execute request handler delegating to DPBTBookDeposit.
     */
    public MO_OUT_DepositBooking handleExecuteRequest(MO_INP_DepositBooking request) {
        return this.DPBTBookDeposit(request);
    }

    /**
     * Generic inquiry request handler delegating to DPETGetDepositDetails.
     */
    public MO_OUT_DepositBooking handleInquiryRequest(String queryKey) {
        return this.DPETGetDepositDetails(queryKey);
    }

    public boolean ping() {
        return true;
    }
}
