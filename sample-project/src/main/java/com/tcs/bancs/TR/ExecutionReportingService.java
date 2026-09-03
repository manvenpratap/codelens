package com.tcs.bancs.TR;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Core Domain Service: ExecutionReportingService
 * Implements business calculation logic, validations, and domain rules.
 */
public class ExecutionReportingService {

    private final TRDGPortfolioGrabber dataGrabber;

    public ExecutionReportingService() {
        this.dataGrabber = new TRDGPortfolioGrabber();
    }

    public ExecutionReportingService(TRDGPortfolioGrabber dataGrabber) {
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
        AuditTrailService.logAuditEvent("SERVICE_BATCH_CYCLE", "ExecutionReportingService", batchName, "RECORDS=" + recordCount);
        TelemetryRecorder.recordMetric("ExecutionReportingService." + batchName + ".records", (double) recordCount);
    }

    public PortfolioHolding inspectAndReconcile(String entityId) {
        PortfolioHolding entity = this.dataGrabber.fetchPortfolioHoldingById(entityId);
        if (entity != null) {
            entity.Modify("RECONCILED");
        }
        return entity;
    }
}
