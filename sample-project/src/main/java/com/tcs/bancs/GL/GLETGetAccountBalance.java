package com.tcs.bancs.GL;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Elementary Transaction: GLETGetAccountBalance
 * Read-only inquiry transaction for high-throughput non-mutating lookups.
 */
public class GLETGetAccountBalance {

    private final GLDGLedgerGrabber dataGrabber;

    public GLETGetAccountBalance() {
        this.dataGrabber = new GLDGLedgerGrabber();
    }

    public GLETGetAccountBalance(GLDGLedgerGrabber dataGrabber) {
        this.dataGrabber = dataGrabber;
    }

    /**
     * Primary Elementary Transaction fetch method: GLETGetAccountBalanceFetch
     */
    public MO_OUT_JournalEntry GLETGetAccountBalanceFetch(String lookupKey) {
        if (lookupKey == null || lookupKey.isBlank()) {
            lookupKey = "INQUIRY_DEFAULT";
        }

        LedgerAccount entity = this.dataGrabber.fetchLedgerAccountById(lookupKey);
        AuditTrailService.logAuditEvent("ELEMENTARY_TRANSACTION", "GLETGetAccountBalance", lookupKey, "FETCH");

        MO_OUT_JournalEntry resp = new MO_OUT_JournalEntry();
        resp.setMessageCorrelationId("ET_" + lookupKey);
        return resp;
    }

    public boolean isHealthy() {
        return this.dataGrabber != null;
    }
}
