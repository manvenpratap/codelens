package com.tcs.bancs.PM;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Core Domain Service: DirectDebitMandateService
 * Implements business calculation logic, validations, and domain rules.
 */
public class DirectDebitMandateService {

    private final PMDGClearingQueueGrabber dataGrabber;

    public DirectDebitMandateService() {
        this.dataGrabber = new PMDGClearingQueueGrabber();
    }

    public DirectDebitMandateService(PMDGClearingQueueGrabber dataGrabber) {
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
        AuditTrailService.logAuditEvent("SERVICE_BATCH_CYCLE", "DirectDebitMandateService", batchName, "RECORDS=" + recordCount);
        TelemetryRecorder.recordMetric("DirectDebitMandateService." + batchName + ".records", (double) recordCount);
    }

    public ClearingReturnRecord inspectAndReconcile(String entityId) {
        ClearingReturnRecord entity = this.dataGrabber.fetchClearingReturnRecordById(entityId);
        if (entity != null) {
            entity.Modify("RECONCILED");
        }
        return entity;
    }
}
