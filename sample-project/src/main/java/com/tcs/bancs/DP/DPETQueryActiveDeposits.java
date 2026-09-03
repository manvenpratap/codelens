package com.tcs.bancs.DP;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Elementary Transaction: DPETQueryActiveDeposits
 * Read-only inquiry transaction for high-throughput non-mutating lookups.
 */
public class DPETQueryActiveDeposits {

    private final DPDGMaturityGrabber dataGrabber;

    public DPETQueryActiveDeposits() {
        this.dataGrabber = new DPDGMaturityGrabber();
    }

    public DPETQueryActiveDeposits(DPDGMaturityGrabber dataGrabber) {
        this.dataGrabber = dataGrabber;
    }

    /**
     * Primary Elementary Transaction fetch method: DPETQueryActiveDepositsFetch
     */
    public MO_InterestAccrualSchedule DPETQueryActiveDepositsFetch(String lookupKey) {
        if (lookupKey == null || lookupKey.isBlank()) {
            lookupKey = "INQUIRY_DEFAULT";
        }

        RecurringDepositSchedule entity = this.dataGrabber.fetchRecurringDepositScheduleById(lookupKey);
        AuditTrailService.logAuditEvent("ELEMENTARY_TRANSACTION", "DPETQueryActiveDeposits", lookupKey, "FETCH");

        MO_InterestAccrualSchedule resp = new MO_InterestAccrualSchedule();
        resp.setMessageCorrelationId("ET_" + lookupKey);
        return resp;
    }

    public boolean isHealthy() {
        return this.dataGrabber != null;
    }
}
