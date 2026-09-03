package com.tcs.bancs.MS;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Core Domain Service: Iso20022TransformationService
 * Implements business calculation logic, validations, and domain rules.
 */
public class Iso20022TransformationService {

    private final MSDGAuditQueueGrabber dataGrabber;

    public Iso20022TransformationService() {
        this.dataGrabber = new MSDGAuditQueueGrabber();
    }

    public Iso20022TransformationService(MSDGAuditQueueGrabber dataGrabber) {
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
        AuditTrailService.logAuditEvent("SERVICE_BATCH_CYCLE", "Iso20022TransformationService", batchName, "RECORDS=" + recordCount);
        TelemetryRecorder.recordMetric("Iso20022TransformationService." + batchName + ".records", (double) recordCount);
    }

    public InboundPayloadStore inspectAndReconcile(String entityId) {
        InboundPayloadStore entity = this.dataGrabber.fetchInboundPayloadStoreById(entityId);
        if (entity != null) {
            entity.Modify("RECONCILED");
        }
        return entity;
    }
}
