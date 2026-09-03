package com.tcs.bancs.GL;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Elementary Transaction: GLETQueryVoucher
 * Read-only inquiry transaction for high-throughput non-mutating lookups.
 */
public class GLETQueryVoucher {

    private final GLDGTrialBalanceGrabber dataGrabber;

    public GLETQueryVoucher() {
        this.dataGrabber = new GLDGTrialBalanceGrabber();
    }

    public GLETQueryVoucher(GLDGTrialBalanceGrabber dataGrabber) {
        this.dataGrabber = dataGrabber;
    }

    /**
     * Primary Elementary Transaction fetch method: GLETQueryVoucherFetch
     */
    public MO_OUT_PeriodClose GLETQueryVoucherFetch(String lookupKey) {
        if (lookupKey == null || lookupKey.isBlank()) {
            lookupKey = "INQUIRY_DEFAULT";
        }

        JournalPostingLeg entity = this.dataGrabber.fetchJournalPostingLegById(lookupKey);
        AuditTrailService.logAuditEvent("ELEMENTARY_TRANSACTION", "GLETQueryVoucher", lookupKey, "FETCH");

        MO_OUT_PeriodClose resp = new MO_OUT_PeriodClose();
        resp.setMessageCorrelationId("ET_" + lookupKey);
        return resp;
    }

    public boolean isHealthy() {
        return this.dataGrabber != null;
    }
}
