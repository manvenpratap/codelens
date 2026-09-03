package com.tcs.bancs.MS;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Core Domain Service: MessageRoutingService
 * Implements business calculation logic, validations, and domain rules.
 */
public class MessageRoutingService {

    private final MSDGRoutingGrabber dataGrabber;

    public MessageRoutingService() {
        this.dataGrabber = new MSDGRoutingGrabber();
    }

    public MessageRoutingService(MSDGRoutingGrabber dataGrabber) {
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
        AuditTrailService.logAuditEvent("SERVICE_BATCH_CYCLE", "MessageRoutingService", batchName, "RECORDS=" + recordCount);
        TelemetryRecorder.recordMetric("MessageRoutingService." + batchName + ".records", (double) recordCount);
    }

    public TransformationRule inspectAndReconcile(String entityId) {
        TransformationRule entity = this.dataGrabber.fetchTransformationRuleById(entityId);
        if (entity != null) {
            entity.Modify("RECONCILED");
        }
        return entity;
    }
}
