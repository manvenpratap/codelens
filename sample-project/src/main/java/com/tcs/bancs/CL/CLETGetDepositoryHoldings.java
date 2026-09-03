package com.tcs.bancs.CL;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Elementary Transaction: CLETGetDepositoryHoldings
 * Read-only inquiry transaction for high-throughput non-mutating lookups.
 */
public class CLETGetDepositoryHoldings {

    private final CLDGCustodyGrabber dataGrabber;

    public CLETGetDepositoryHoldings() {
        this.dataGrabber = new CLDGCustodyGrabber();
    }

    public CLETGetDepositoryHoldings(CLDGCustodyGrabber dataGrabber) {
        this.dataGrabber = dataGrabber;
    }

    /**
     * Primary Elementary Transaction fetch method: CLETGetDepositoryHoldingsFetch
     */
    public MO_OUT_Affirmation CLETGetDepositoryHoldingsFetch(String lookupKey) {
        if (lookupKey == null || lookupKey.isBlank()) {
            lookupKey = "INQUIRY_DEFAULT";
        }

        DepositoryAccount entity = this.dataGrabber.fetchDepositoryAccountById(lookupKey);
        AuditTrailService.logAuditEvent("ELEMENTARY_TRANSACTION", "CLETGetDepositoryHoldings", lookupKey, "FETCH");

        MO_OUT_Affirmation resp = new MO_OUT_Affirmation();
        resp.setMessageCorrelationId("ET_" + lookupKey);
        return resp;
    }

    public boolean isHealthy() {
        return this.dataGrabber != null;
    }
}
