package com.tcs.bancs.DP;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Elementary Transaction: DPETGetDepositDetails
 * Read-only inquiry transaction for high-throughput non-mutating lookups.
 */
public class DPETGetDepositDetails {

    private final DPDGDepositGrabber dataGrabber;

    public DPETGetDepositDetails() {
        this.dataGrabber = new DPDGDepositGrabber();
    }

    public DPETGetDepositDetails(DPDGDepositGrabber dataGrabber) {
        this.dataGrabber = dataGrabber;
    }

    /**
     * Primary Elementary Transaction fetch method: DPETGetDepositDetailsFetch
     */
    public MO_OUT_DepositBooking DPETGetDepositDetailsFetch(String lookupKey) {
        if (lookupKey == null || lookupKey.isBlank()) {
            lookupKey = "INQUIRY_DEFAULT";
        }

        DepositContract entity = this.dataGrabber.fetchDepositContractById(lookupKey);
        AuditTrailService.logAuditEvent("ELEMENTARY_TRANSACTION", "DPETGetDepositDetails", lookupKey, "FETCH");

        MO_OUT_DepositBooking resp = new MO_OUT_DepositBooking();
        resp.setMessageCorrelationId("ET_" + lookupKey);
        return resp;
    }

    public boolean isHealthy() {
        return this.dataGrabber != null;
    }
}
