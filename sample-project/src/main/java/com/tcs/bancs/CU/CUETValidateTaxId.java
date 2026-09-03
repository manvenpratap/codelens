package com.tcs.bancs.CU;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Elementary Transaction: CUETValidateTaxId
 * Read-only inquiry transaction for high-throughput non-mutating lookups.
 */
public class CUETValidateTaxId {

    private final CUDGExposureRollupGrabber dataGrabber;

    public CUETValidateTaxId() {
        this.dataGrabber = new CUDGExposureRollupGrabber();
    }

    public CUETValidateTaxId(CUDGExposureRollupGrabber dataGrabber) {
        this.dataGrabber = dataGrabber;
    }

    /**
     * Primary Elementary Transaction fetch method: CUETValidateTaxIdFetch
     */
    public MO_KycDocumentSummary CUETValidateTaxIdFetch(String lookupKey) {
        if (lookupKey == null || lookupKey.isBlank()) {
            lookupKey = "INQUIRY_DEFAULT";
        }

        CustomerPepScreening entity = this.dataGrabber.fetchCustomerPepScreeningById(lookupKey);
        AuditTrailService.logAuditEvent("ELEMENTARY_TRANSACTION", "CUETValidateTaxId", lookupKey, "FETCH");

        MO_KycDocumentSummary resp = new MO_KycDocumentSummary();
        resp.setMessageCorrelationId("ET_" + lookupKey);
        return resp;
    }

    public boolean isHealthy() {
        return this.dataGrabber != null;
    }
}
