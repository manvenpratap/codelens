package com.tcs.bancs.LN;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Core Domain Service: RepaymentService
 * Implements business calculation logic, validations, and domain rules.
 */
public class RepaymentService {

    private final LNDGDelinquencyGrabber dataGrabber;

    public RepaymentService() {
        this.dataGrabber = new LNDGDelinquencyGrabber();
    }

    public RepaymentService(LNDGDelinquencyGrabber dataGrabber) {
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
        AuditTrailService.logAuditEvent("SERVICE_BATCH_CYCLE", "RepaymentService", batchName, "RECORDS=" + recordCount);
        TelemetryRecorder.recordMetric("RepaymentService." + batchName + ".records", (double) recordCount);
    }

    public LoanDisbursementTranche inspectAndReconcile(String entityId) {
        LoanDisbursementTranche entity = this.dataGrabber.fetchLoanDisbursementTrancheById(entityId);
        if (entity != null) {
            entity.Modify("RECONCILED");
        }
        return entity;
    }
}
