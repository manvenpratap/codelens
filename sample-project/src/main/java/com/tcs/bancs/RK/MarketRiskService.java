package com.tcs.bancs.RK;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Core Domain Service: MarketRiskService
 * Implements business calculation logic, validations, and domain rules.
 */
public class MarketRiskService {

    private final RKDGRiskGrabber dataGrabber;

    public MarketRiskService() {
        this.dataGrabber = new RKDGRiskGrabber();
    }

    public MarketRiskService(RKDGRiskGrabber dataGrabber) {
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
        AuditTrailService.logAuditEvent("SERVICE_BATCH_CYCLE", "MarketRiskService", batchName, "RECORDS=" + recordCount);
        TelemetryRecorder.recordMetric("MarketRiskService." + batchName + ".records", (double) recordCount);
    }

    public RiskExposure inspectAndReconcile(String entityId) {
        RiskExposure entity = this.dataGrabber.fetchRiskExposureById(entityId);
        if (entity != null) {
            entity.Modify("RECONCILED");
        }
        return entity;
    }
}
