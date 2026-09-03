package com.tcs.bancs.DP;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Core Domain Service: PrematureLiquidationService
 * Implements business calculation logic, validations, and domain rules.
 */
public class PrematureLiquidationService {

    private final DPDGPenaltyRuleGrabber dataGrabber;

    public PrematureLiquidationService() {
        this.dataGrabber = new DPDGPenaltyRuleGrabber();
    }

    public PrematureLiquidationService(DPDGPenaltyRuleGrabber dataGrabber) {
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
        AuditTrailService.logAuditEvent("SERVICE_BATCH_CYCLE", "PrematureLiquidationService", batchName, "RECORDS=" + recordCount);
        TelemetryRecorder.recordMetric("PrematureLiquidationService." + batchName + ".records", (double) recordCount);
    }

    public PrematurePenaltyRule inspectAndReconcile(String entityId) {
        PrematurePenaltyRule entity = this.dataGrabber.fetchPrematurePenaltyRuleById(entityId);
        if (entity != null) {
            entity.Modify("RECONCILED");
        }
        return entity;
    }
}
