package com.tcs.bancs.CU;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Elementary Transaction: CUETFetchRelationships
 * Read-only inquiry transaction for high-throughput non-mutating lookups.
 */
public class CUETFetchRelationships {

    private final CUDGRelationshipGrabber dataGrabber;

    public CUETFetchRelationships() {
        this.dataGrabber = new CUDGRelationshipGrabber();
    }

    public CUETFetchRelationships(CUDGRelationshipGrabber dataGrabber) {
        this.dataGrabber = dataGrabber;
    }

    /**
     * Primary Elementary Transaction fetch method: CUETFetchRelationshipsFetch
     */
    public MO_OUT_RiskRatingUpdate CUETFetchRelationshipsFetch(String lookupKey) {
        if (lookupKey == null || lookupKey.isBlank()) {
            lookupKey = "INQUIRY_DEFAULT";
        }

        PartyRelationship entity = this.dataGrabber.fetchPartyRelationshipById(lookupKey);
        AuditTrailService.logAuditEvent("ELEMENTARY_TRANSACTION", "CUETFetchRelationships", lookupKey, "FETCH");

        MO_OUT_RiskRatingUpdate resp = new MO_OUT_RiskRatingUpdate();
        resp.setMessageCorrelationId("ET_" + lookupKey);
        return resp;
    }

    public boolean isHealthy() {
        return this.dataGrabber != null;
    }
}
