package com.tcs.bancs.GL;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Core Domain Service: TrialBalanceCalculationService
 * Implements business calculation logic, validations, and domain rules.
 */
public class TrialBalanceCalculationService {

    private final GLDGTrialBalanceGrabber dataGrabber;

    public TrialBalanceCalculationService() {
        this.dataGrabber = new GLDGTrialBalanceGrabber();
    }

    public TrialBalanceCalculationService(GLDGTrialBalanceGrabber dataGrabber) {
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
        AuditTrailService.logAuditEvent("SERVICE_BATCH_CYCLE", "TrialBalanceCalculationService", batchName, "RECORDS=" + recordCount);
        TelemetryRecorder.recordMetric("TrialBalanceCalculationService." + batchName + ".records", (double) recordCount);
    }

    public JournalPostingLeg inspectAndReconcile(String entityId) {
        JournalPostingLeg entity = this.dataGrabber.fetchJournalPostingLegById(entityId);
        if (entity != null) {
            entity.Modify("RECONCILED");
        }
        return entity;
    }
}
