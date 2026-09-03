package com.tcs.bancs.LN;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Core Domain Service: LoanOriginationService
 * Implements business calculation logic, validations, and domain rules.
 */
public class LoanOriginationService {

    private final LNDGLoanGrabber dataGrabber;

    public LoanOriginationService() {
        this.dataGrabber = new LNDGLoanGrabber();
    }

    public LoanOriginationService(LNDGLoanGrabber dataGrabber) {
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
        AuditTrailService.logAuditEvent("SERVICE_BATCH_CYCLE", "LoanOriginationService", batchName, "RECORDS=" + recordCount);
        TelemetryRecorder.recordMetric("LoanOriginationService." + batchName + ".records", (double) recordCount);
    }

    public Loan inspectAndReconcile(String entityId) {
        Loan entity = this.dataGrabber.fetchLoanById(entityId);
        if (entity != null) {
            entity.Modify("RECONCILED");
        }
        return entity;
    }
}
