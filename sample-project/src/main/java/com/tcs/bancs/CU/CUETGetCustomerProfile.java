package com.tcs.bancs.CU;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Elementary Transaction: CUETGetCustomerProfile
 * Read-only inquiry transaction for high-throughput non-mutating lookups.
 */
public class CUETGetCustomerProfile {

    private final CUDGCustomerGrabber dataGrabber;

    public CUETGetCustomerProfile() {
        this.dataGrabber = new CUDGCustomerGrabber();
    }

    public CUETGetCustomerProfile(CUDGCustomerGrabber dataGrabber) {
        this.dataGrabber = dataGrabber;
    }

    /**
     * Primary Elementary Transaction fetch method: CUETGetCustomerProfileFetch
     */
    public MO_OUT_CustomerOnboarding CUETGetCustomerProfileFetch(String lookupKey) {
        if (lookupKey == null || lookupKey.isBlank()) {
            lookupKey = "INQUIRY_DEFAULT";
        }

        CustomerProfile entity = this.dataGrabber.fetchCustomerProfileById(lookupKey);
        AuditTrailService.logAuditEvent("ELEMENTARY_TRANSACTION", "CUETGetCustomerProfile", lookupKey, "FETCH");

        MO_OUT_CustomerOnboarding resp = new MO_OUT_CustomerOnboarding();
        resp.setMessageCorrelationId("ET_" + lookupKey);
        return resp;
    }

    public boolean isHealthy() {
        return this.dataGrabber != null;
    }
}
