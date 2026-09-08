package com.tcs.bancs.GL;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Inbound Channel Controller: GeneralLedgerController
 * Dispatches inbound requests to primary service transaction methods.
 */
public class GeneralLedgerController {

    private final GeneralLedgerService service;

    public GeneralLedgerController() {
        this.service = new GeneralLedgerService();
    }

    public GeneralLedgerController(GeneralLedgerService service) {
        this.service = service;
    }

    /**
     * Inbound Business Transaction dispatch method: GLBTPostJournalEntry
     */
    public MO_OUT_JournalEntry GLBTPostJournalEntry(MO_INP_JournalEntry request) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "GeneralLedgerController", request != null ? request.getMessageCorrelationId() : "", "MUTATION");
        return this.service.GLBTPostJournalEntry(request);
    }

    /**
     * Inbound Elementary Transaction dispatch method: GLETGetAccountBalance
     */
    public MO_OUT_JournalEntry GLETGetAccountBalance(String queryKey) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "GeneralLedgerController", queryKey, "INQUIRY");
        return this.service.GLETGetAccountBalance(queryKey);
    }

    /**
     * Generic execute request handler delegating to GLBTPostJournalEntry.
     */
    public MO_OUT_JournalEntry handleExecuteRequest(MO_INP_JournalEntry request) {
        return this.GLBTPostJournalEntry(request);
    }

    /**
     * Generic inquiry request handler delegating to GLETGetAccountBalance.
     */
    public MO_OUT_JournalEntry handleInquiryRequest(String queryKey) {
        return this.GLETGetAccountBalance(queryKey);
    }

    public boolean ping() {
        return true;
    }
}
