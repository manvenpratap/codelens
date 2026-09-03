package com.tcs.bancs.GL;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Inbound Channel Controller: FiscalPeriodController
 * Dispatches inbound requests from REST, Branch, ISO, and FIX channels.
 */
public class FiscalPeriodController {

    private final GLBTCloseFiscalPeriod businessTransaction;
    private final GLETQueryVoucher elementaryTransaction;

    public FiscalPeriodController() {
        this.businessTransaction = new GLBTCloseFiscalPeriod();
        this.elementaryTransaction = new GLETQueryVoucher();
    }

    public FiscalPeriodController(GLBTCloseFiscalPeriod bt, GLETQueryVoucher et) {
        this.businessTransaction = bt;
        this.elementaryTransaction = et;
    }

    /**
     * Inbound mutating command handler.
     */
    public MO_OUT_PeriodClose handleExecuteRequest(MO_INP_PeriodClose request) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "FiscalPeriodController", request.getMessageCorrelationId(), "MUTATION");
        return this.businessTransaction.GLBTCloseFiscalPeriodExecute(request);
    }

    /**
     * Inbound read-only query handler.
     */
    public MO_OUT_PeriodClose handleInquiryRequest(String queryKey) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "FiscalPeriodController", queryKey, "INQUIRY");
        return this.elementaryTransaction.GLETQueryVoucherFetch(queryKey);
    }

    public boolean ping() {
        return true;
    }
}
