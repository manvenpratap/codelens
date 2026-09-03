package com.tcs.bancs.GL;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Core Domain Service: CostCenterAllocationService
 * Implements business calculation logic, validations, and domain rules.
 */
public class CostCenterAllocationService {

    private final GLDGLedgerGrabber dataGrabber;

    public CostCenterAllocationService() {
        this.dataGrabber = new GLDGLedgerGrabber();
    }

    public CostCenterAllocationService(GLDGLedgerGrabber dataGrabber) {
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
        AuditTrailService.logAuditEvent("SERVICE_BATCH_CYCLE", "CostCenterAllocationService", batchName, "RECORDS=" + recordCount);
        TelemetryRecorder.recordMetric("CostCenterAllocationService." + batchName + ".records", (double) recordCount);
    }

    public LedgerAccount inspectAndReconcile(String entityId) {
        LedgerAccount entity = this.dataGrabber.fetchLedgerAccountById(entityId);
        if (entity != null) {
            entity.Modify("RECONCILED");
        }
        return entity;
    }
}
