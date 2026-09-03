package com.tcs.bancs.GL;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Inbound Channel Controller: GeneralLedgerController
 * Dispatches inbound requests from REST, Branch, ISO, and FIX channels.
 */
public class GeneralLedgerController {

    private final GLBTPostJournalEntry businessTransaction;
    private final GLETGetAccountBalance elementaryTransaction;

    public GeneralLedgerController() {
        this.businessTransaction = new GLBTPostJournalEntry();
        this.elementaryTransaction = new GLETGetAccountBalance();
    }

    public GeneralLedgerController(GLBTPostJournalEntry bt, GLETGetAccountBalance et) {
        this.businessTransaction = bt;
        this.elementaryTransaction = et;
    }

    /**
     * Inbound mutating command handler.
     */
    public MO_OUT_JournalEntry handleExecuteRequest(MO_INP_JournalEntry request) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "GeneralLedgerController", request.getMessageCorrelationId(), "MUTATION");
        return this.businessTransaction.GLBTPostJournalEntryExecute(request);
    }

    /**
     * Inbound read-only query handler.
     */
    public MO_OUT_JournalEntry handleInquiryRequest(String queryKey) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "GeneralLedgerController", queryKey, "INQUIRY");
        return this.elementaryTransaction.GLETGetAccountBalanceFetch(queryKey);
    }

    public boolean ping() {
        return true;
    }
}
