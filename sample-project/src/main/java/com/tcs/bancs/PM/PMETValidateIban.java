package com.tcs.bancs.PM;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Elementary Transaction: PMETValidateIban
 * Read-only inquiry transaction for high-throughput non-mutating lookups.
 */
public class PMETValidateIban {

    private final PMDGMandateGrabber dataGrabber;

    public PMETValidateIban() {
        this.dataGrabber = new PMDGMandateGrabber();
    }

    public PMETValidateIban(PMDGMandateGrabber dataGrabber) {
        this.dataGrabber = dataGrabber;
    }

    /**
     * Primary Elementary Transaction fetch method: PMETValidateIbanFetch
     */
    public MO_OUT_PaymentCancellation PMETValidateIbanFetch(String lookupKey) {
        if (lookupKey == null || lookupKey.isBlank()) {
            lookupKey = "INQUIRY_DEFAULT";
        }

        PaymentMandate entity = this.dataGrabber.fetchPaymentMandateById(lookupKey);
        AuditTrailService.logAuditEvent("ELEMENTARY_TRANSACTION", "PMETValidateIban", lookupKey, "FETCH");

        MO_OUT_PaymentCancellation resp = new MO_OUT_PaymentCancellation();
        resp.setMessageCorrelationId("ET_" + lookupKey);
        return resp;
    }

    public boolean isHealthy() {
        return this.dataGrabber != null;
    }
}
