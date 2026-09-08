package com.tcs.bancs.GL;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Inbound Channel Controller: FiscalPeriodController
 * Dispatches inbound requests to primary service transaction methods.
 */
public class FiscalPeriodController {

    private final GeneralLedgerService service;

    public FiscalPeriodController() {
        this.service = new GeneralLedgerService();
    }

    public FiscalPeriodController(GeneralLedgerService service) {
        this.service = service;
    }

    /**
     * Inbound Business Transaction dispatch method: GLBTCloseFiscalPeriod
     */
    public MO_OUT_PeriodClose GLBTCloseFiscalPeriod(MO_INP_PeriodClose request) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "FiscalPeriodController", request != null ? request.getMessageCorrelationId() : "", "MUTATION");
        return this.service.GLBTCloseFiscalPeriod(request);
    }

    /**
     * Inbound Elementary Transaction dispatch method: GLETQueryVoucher
     */
    public MO_OUT_PeriodClose GLETQueryVoucher(String queryKey) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "FiscalPeriodController", queryKey, "INQUIRY");
        return this.service.GLETQueryVoucher(queryKey);
    }

    /**
     * Generic execute request handler delegating to GLBTCloseFiscalPeriod.
     */
    public MO_OUT_PeriodClose handleExecuteRequest(MO_INP_PeriodClose request) {
        return this.GLBTCloseFiscalPeriod(request);
    }

    /**
     * Generic inquiry request handler delegating to GLETQueryVoucher.
     */
    public MO_OUT_PeriodClose handleInquiryRequest(String queryKey) {
        return this.GLETQueryVoucher(queryKey);
    }

    public boolean ping() {
        return true;
    }
}
