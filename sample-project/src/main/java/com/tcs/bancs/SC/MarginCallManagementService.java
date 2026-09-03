package com.tcs.bancs.SC;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Core Domain Service: MarginCallManagementService
 * Implements business calculation logic, validations, and domain rules.
 */
public class MarginCallManagementService {

    private final SCDGValuationGrabber dataGrabber;

    public MarginCallManagementService() {
        this.dataGrabber = new SCDGValuationGrabber();
    }

    public MarginCallManagementService(SCDGValuationGrabber dataGrabber) {
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
        AuditTrailService.logAuditEvent("SERVICE_BATCH_CYCLE", "MarginCallManagementService", batchName, "RECORDS=" + recordCount);
        TelemetryRecorder.recordMetric("MarginCallManagementService." + batchName + ".records", (double) recordCount);
    }

    public ValuationAppraisalReport inspectAndReconcile(String entityId) {
        ValuationAppraisalReport entity = this.dataGrabber.fetchValuationAppraisalReportById(entityId);
        if (entity != null) {
            entity.Modify("RECONCILED");
        }
        return entity;
    }
}
