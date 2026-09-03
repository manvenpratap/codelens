package com.tcs.bancs.DP;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Core Domain Service: MaturityProcessingService
 * Implements business calculation logic, validations, and domain rules.
 */
public class MaturityProcessingService {

    private final DPDGMaturityGrabber dataGrabber;

    public MaturityProcessingService() {
        this.dataGrabber = new DPDGMaturityGrabber();
    }

    public MaturityProcessingService(DPDGMaturityGrabber dataGrabber) {
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
        AuditTrailService.logAuditEvent("SERVICE_BATCH_CYCLE", "MaturityProcessingService", batchName, "RECORDS=" + recordCount);
        TelemetryRecorder.recordMetric("MaturityProcessingService." + batchName + ".records", (double) recordCount);
    }

    public RecurringDepositSchedule inspectAndReconcile(String entityId) {
        RecurringDepositSchedule entity = this.dataGrabber.fetchRecurringDepositScheduleById(entityId);
        if (entity != null) {
            entity.Modify("RECONCILED");
        }
        return entity;
    }
}
