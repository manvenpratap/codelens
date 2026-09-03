package com.tcs.bancs.PM;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Core Domain Service: ClearingNetworkAdapterService
 * Implements business calculation logic, validations, and domain rules.
 */
public class ClearingNetworkAdapterService {

    private final PMDGRoutingGrabber dataGrabber;

    public ClearingNetworkAdapterService() {
        this.dataGrabber = new PMDGRoutingGrabber();
    }

    public ClearingNetworkAdapterService(PMDGRoutingGrabber dataGrabber) {
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
        AuditTrailService.logAuditEvent("SERVICE_BATCH_CYCLE", "ClearingNetworkAdapterService", batchName, "RECORDS=" + recordCount);
        TelemetryRecorder.recordMetric("ClearingNetworkAdapterService." + batchName + ".records", (double) recordCount);
    }

    public RoutingDirectory inspectAndReconcile(String entityId) {
        RoutingDirectory entity = this.dataGrabber.fetchRoutingDirectoryById(entityId);
        if (entity != null) {
            entity.Modify("RECONCILED");
        }
        return entity;
    }
}
