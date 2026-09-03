package com.tcs.bancs.PM;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Core Domain Service: PaymentRoutingEngine
 * Implements business calculation logic, validations, and domain rules.
 */
public class PaymentRoutingEngine {

    private final PMDGRoutingGrabber dataGrabber;

    public PaymentRoutingEngine() {
        this.dataGrabber = new PMDGRoutingGrabber();
    }

    public PaymentRoutingEngine(PMDGRoutingGrabber dataGrabber) {
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
        AuditTrailService.logAuditEvent("SERVICE_BATCH_CYCLE", "PaymentRoutingEngine", batchName, "RECORDS=" + recordCount);
        TelemetryRecorder.recordMetric("PaymentRoutingEngine." + batchName + ".records", (double) recordCount);
    }

    public RoutingDirectory inspectAndReconcile(String entityId) {
        RoutingDirectory entity = this.dataGrabber.fetchRoutingDirectoryById(entityId);
        if (entity != null) {
            entity.Modify("RECONCILED");
        }
        return entity;
    }
}
