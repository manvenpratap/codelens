package com.tcs.bancs.MS;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Elementary Transaction: MSETValidateSchema
 * Read-only inquiry transaction for high-throughput non-mutating lookups.
 */
public class MSETValidateSchema {

    private final MSDGRoutingGrabber dataGrabber;

    public MSETValidateSchema() {
        this.dataGrabber = new MSDGRoutingGrabber();
    }

    public MSETValidateSchema(MSDGRoutingGrabber dataGrabber) {
        this.dataGrabber = dataGrabber;
    }

    /**
     * Primary Elementary Transaction fetch method: MSETValidateSchemaFetch
     */
    public MO_OUT_TransformMessage MSETValidateSchemaFetch(String lookupKey) {
        if (lookupKey == null || lookupKey.isBlank()) {
            lookupKey = "INQUIRY_DEFAULT";
        }

        TransformationRule entity = this.dataGrabber.fetchTransformationRuleById(lookupKey);
        AuditTrailService.logAuditEvent("ELEMENTARY_TRANSACTION", "MSETValidateSchema", lookupKey, "FETCH");

        MO_OUT_TransformMessage resp = new MO_OUT_TransformMessage();
        resp.setMessageCorrelationId("ET_" + lookupKey);
        return resp;
    }

    public boolean isHealthy() {
        return this.dataGrabber != null;
    }
}
