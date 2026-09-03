package com.tcs.bancs.PM;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Core Domain Service: FeeDeductionService
 * Implements business calculation logic, validations, and domain rules.
 */
public class FeeDeductionService {

    private final PMDGPaymentGrabber dataGrabber;

    public FeeDeductionService() {
        this.dataGrabber = new PMDGPaymentGrabber();
    }

    public FeeDeductionService(PMDGPaymentGrabber dataGrabber) {
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
        AuditTrailService.logAuditEvent("SERVICE_BATCH_CYCLE", "FeeDeductionService", batchName, "RECORDS=" + recordCount);
        TelemetryRecorder.recordMetric("FeeDeductionService." + batchName + ".records", (double) recordCount);
    }

    public PaymentTransaction inspectAndReconcile(String entityId) {
        PaymentTransaction entity = this.dataGrabber.fetchPaymentTransactionById(entityId);
        if (entity != null) {
            entity.Modify("RECONCILED");
        }
        return entity;
    }
}
