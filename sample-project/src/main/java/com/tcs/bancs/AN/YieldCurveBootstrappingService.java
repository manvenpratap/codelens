package com.tcs.bancs.AN;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Core Domain Service: YieldCurveBootstrappingService
 * Implements business calculation logic, validations, and domain rules.
 */
public class YieldCurveBootstrappingService {

    private final ANDGYieldCurveGrabber dataGrabber;

    public YieldCurveBootstrappingService() {
        this.dataGrabber = new ANDGYieldCurveGrabber();
    }

    public YieldCurveBootstrappingService(ANDGYieldCurveGrabber dataGrabber) {
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
        AuditTrailService.logAuditEvent("SERVICE_BATCH_CYCLE", "YieldCurveBootstrappingService", batchName, "RECORDS=" + recordCount);
        TelemetryRecorder.recordMetric("YieldCurveBootstrappingService." + batchName + ".records", (double) recordCount);
    }

    public YieldCurveSnapshot inspectAndReconcile(String entityId) {
        YieldCurveSnapshot entity = this.dataGrabber.fetchYieldCurveSnapshotById(entityId);
        if (entity != null) {
            entity.Modify("RECONCILED");
        }
        return entity;
    }
}
