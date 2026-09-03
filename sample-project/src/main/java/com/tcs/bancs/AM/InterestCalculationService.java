package com.tcs.bancs.AM;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Core Domain Service: InterestCalculationService
 * Implements business calculation logic, validations, and domain rules.
 */
public class InterestCalculationService {

    private final AMDGBalanceGrabber dataGrabber;

    public InterestCalculationService() {
        this.dataGrabber = new AMDGBalanceGrabber();
    }

    public InterestCalculationService(AMDGBalanceGrabber dataGrabber) {
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
        AuditTrailService.logAuditEvent("SERVICE_BATCH_CYCLE", "InterestCalculationService", batchName, "RECORDS=" + recordCount);
        TelemetryRecorder.recordMetric("InterestCalculationService." + batchName + ".records", (double) recordCount);
    }

    public AccountLimit inspectAndReconcile(String entityId) {
        AccountLimit entity = this.dataGrabber.fetchAccountLimitById(entityId);
        if (entity != null) {
            entity.Modify("RECONCILED");
        }
        return entity;
    }
}
