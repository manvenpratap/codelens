package com.tcs.bancs.AM;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Elementary Transaction: AMETValidateAccount
 * Read-only inquiry transaction for high-throughput non-mutating lookups.
 */
public class AMETValidateAccount {

    private final AMDGStatementGrabber dataGrabber;

    public AMETValidateAccount() {
        this.dataGrabber = new AMDGStatementGrabber();
    }

    public AMETValidateAccount(AMDGStatementGrabber dataGrabber) {
        this.dataGrabber = dataGrabber;
    }

    /**
     * Primary Elementary Transaction fetch method: AMETValidateAccountFetch
     */
    public MO_OUT_AccountClosure AMETValidateAccountFetch(String lookupKey) {
        if (lookupKey == null || lookupKey.isBlank()) {
            lookupKey = "INQUIRY_DEFAULT";
        }

        AccountFeeSchedule entity = this.dataGrabber.fetchAccountFeeScheduleById(lookupKey);
        AuditTrailService.logAuditEvent("ELEMENTARY_TRANSACTION", "AMETValidateAccount", lookupKey, "FETCH");

        MO_OUT_AccountClosure resp = new MO_OUT_AccountClosure();
        resp.setMessageCorrelationId("ET_" + lookupKey);
        return resp;
    }

    public boolean isHealthy() {
        return this.dataGrabber != null;
    }
}
