package com.tcs.bancs.CU;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Elementary Transaction: CUETQueryKycStatus
 * Read-only inquiry transaction for high-throughput non-mutating lookups.
 */
public class CUETQueryKycStatus {

    private final CUDGKycGrabber dataGrabber;

    public CUETQueryKycStatus() {
        this.dataGrabber = new CUDGKycGrabber();
    }

    public CUETQueryKycStatus(CUDGKycGrabber dataGrabber) {
        this.dataGrabber = dataGrabber;
    }

    /**
     * Primary Elementary Transaction fetch method: CUETQueryKycStatusFetch
     */
    public MO_OUT_KycSubmission CUETQueryKycStatusFetch(String lookupKey) {
        if (lookupKey == null || lookupKey.isBlank()) {
            lookupKey = "INQUIRY_DEFAULT";
        }

        KycDocument entity = this.dataGrabber.fetchKycDocumentById(lookupKey);
        AuditTrailService.logAuditEvent("ELEMENTARY_TRANSACTION", "CUETQueryKycStatus", lookupKey, "FETCH");

        MO_OUT_KycSubmission resp = new MO_OUT_KycSubmission();
        resp.setMessageCorrelationId("ET_" + lookupKey);
        return resp;
    }

    public boolean isHealthy() {
        return this.dataGrabber != null;
    }
}
