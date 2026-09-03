package com.tcs.bancs.PM;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Elementary Transaction: PMETGetPaymentStatus
 * Read-only inquiry transaction for high-throughput non-mutating lookups.
 */
public class PMETGetPaymentStatus {

    private final PMDGPaymentGrabber dataGrabber;

    public PMETGetPaymentStatus() {
        this.dataGrabber = new PMDGPaymentGrabber();
    }

    public PMETGetPaymentStatus(PMDGPaymentGrabber dataGrabber) {
        this.dataGrabber = dataGrabber;
    }

    /**
     * Primary Elementary Transaction fetch method: PMETGetPaymentStatusFetch
     */
    public MO_OUT_PaymentInitiation PMETGetPaymentStatusFetch(String lookupKey) {
        if (lookupKey == null || lookupKey.isBlank()) {
            lookupKey = "INQUIRY_DEFAULT";
        }

        PaymentTransaction entity = this.dataGrabber.fetchPaymentTransactionById(lookupKey);
        AuditTrailService.logAuditEvent("ELEMENTARY_TRANSACTION", "PMETGetPaymentStatus", lookupKey, "FETCH");

        MO_OUT_PaymentInitiation resp = new MO_OUT_PaymentInitiation();
        resp.setMessageCorrelationId("ET_" + lookupKey);
        return resp;
    }

    public boolean isHealthy() {
        return this.dataGrabber != null;
    }
}
