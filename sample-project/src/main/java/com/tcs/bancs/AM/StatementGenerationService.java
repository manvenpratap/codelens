package com.tcs.bancs.AM;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Core Domain Service: StatementGenerationService
 * Implements business calculation logic, validations, and domain rules.
 */
public class StatementGenerationService {

    private final AMDGAccountGrabber dataGrabber;

    public StatementGenerationService() {
        this.dataGrabber = new AMDGAccountGrabber();
    }

    public StatementGenerationService(AMDGAccountGrabber dataGrabber) {
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
        AuditTrailService.logAuditEvent("SERVICE_BATCH_CYCLE", "StatementGenerationService", batchName, "RECORDS=" + recordCount);
        TelemetryRecorder.recordMetric("StatementGenerationService." + batchName + ".records", (double) recordCount);
    }

    public Account inspectAndReconcile(String entityId) {
        Account entity = this.dataGrabber.fetchAccountById(entityId);
        if (entity != null) {
            entity.Modify("RECONCILED");
        }
        return entity;
    }
}
