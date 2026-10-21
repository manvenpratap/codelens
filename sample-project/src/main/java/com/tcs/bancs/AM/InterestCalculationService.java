package com.tcs.bancs.AM;

import java.util.*;
import com.tcs.bancs.common.*;
import com.tcs.bancs.GL.*;
import com.tcs.bancs.CU.*;

/**
 * TCS BaNCS Core Domain Service: InterestCalculationService
 * Implements transaction methods (ET/BT), tasks (TO/TC), and domain calculations.
 */
public class InterestCalculationService {

    private final AMDGBalanceGetter dataGetter;

    public InterestCalculationService() {
        this.dataGetter = new AMDGBalanceGetter();
    }

    public InterestCalculationService(AMDGBalanceGetter dataGetter) {
        this.dataGetter = dataGetter;
    }

    public boolean validateTransactionPreconditions(String contextId) {
        if (contextId == null || contextId.isEmpty()) {
            return false;
        }
        return this.dataGetter.exists(contextId);
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
        AccountLimit entity = this.dataGetter.fetchAccountLimitById(entityId);
        if (entity != null) {
            entity.Modify("RECONCILED");
        }
        return entity;
    }

    /**
     * TCS BaNCS Own Task delegate: AMTOComputeDailyAccrual
     */
    public boolean AMTOComputeDailyAccrual(String correlationId) {
        AuditTrailService.logAuditEvent("TASK_OWN", "AMTOComputeDailyAccrual", correlationId, "SECONDARY_SERVICE");
        return this.dataGrabber != null && this.dataGetter.exists(correlationId);
    }

    /**
     * TCS BaNCS Common Task delegate: AMTCAuditTransaction
     */
    public void AMTCAuditTransaction(String operation, String correlationId) {
        AuditTrailService.logAuditEvent("TASK_COMMON", "AMTCAuditTransaction", correlationId, operation);
    }
}
