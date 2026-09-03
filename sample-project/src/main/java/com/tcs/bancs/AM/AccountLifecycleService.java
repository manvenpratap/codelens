package com.tcs.bancs.AM;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Core Domain Service: AccountLifecycleService
 * Implements business calculation logic, validations, and domain rules.
 */
public class AccountLifecycleService {

    private final AMDGStatementGrabber dataGrabber;

    public AccountLifecycleService() {
        this.dataGrabber = new AMDGStatementGrabber();
    }

    public AccountLifecycleService(AMDGStatementGrabber dataGrabber) {
        this.dataGrabber = dataGrabber;
    }

    public boolean validateTransactionPreconditions(String contextId) {
        if (contextId == null || contextId.isEmpty()) {
            return false;
        }
        return this.dataGrabber.exists(contextId);
    }

    public double calculateInterestOrCharges(double baseAmount, double rate) {
        if (baseAmount <= 0.0 || rate < 0.0) {
            return 0.0;
        }
        return (baseAmount * rate) / 100.0;
    }

    public void executeBatchProcessingCycle(String batchName, int recordCount) {
        AuditTrailService.logAuditEvent("SERVICE_BATCH_CYCLE", "AccountLifecycleService", batchName, "RECORDS=" + recordCount);
        TelemetryRecorder.recordMetric("AccountLifecycleService." + batchName + ".records", (double) recordCount);
    }

    public AccountFeeSchedule inspectAndReconcile(String entityId) {
        AccountFeeSchedule entity = this.dataGrabber.fetchAccountFeeScheduleById(entityId);
        if (entity != null) {
            entity.Modify("RECONCILED");
        }
        return entity;
    }
}
