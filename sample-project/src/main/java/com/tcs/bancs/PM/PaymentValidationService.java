package com.tcs.bancs.PM;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Core Domain Service: PaymentValidationService
 * Implements business calculation logic, validations, and domain rules.
 */
public class PaymentValidationService {

    private final PMDGMandateGrabber dataGrabber;

    public PaymentValidationService() {
        this.dataGrabber = new PMDGMandateGrabber();
    }

    public PaymentValidationService(PMDGMandateGrabber dataGrabber) {
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
        AuditTrailService.logAuditEvent("SERVICE_BATCH_CYCLE", "PaymentValidationService", batchName, "RECORDS=" + recordCount);
        TelemetryRecorder.recordMetric("PaymentValidationService." + batchName + ".records", (double) recordCount);
    }

    public PaymentMandate inspectAndReconcile(String entityId) {
        PaymentMandate entity = this.dataGrabber.fetchPaymentMandateById(entityId);
        if (entity != null) {
            entity.Modify("RECONCILED");
        }
        return entity;
    }
}
