package com.tcs.bancs.GL;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Core Domain Service: JournalPostingService
 * Implements business calculation logic, validations, and domain rules.
 */
public class JournalPostingService {

    private final GLDGVoucherGrabber dataGrabber;

    public JournalPostingService() {
        this.dataGrabber = new GLDGVoucherGrabber();
    }

    public JournalPostingService(GLDGVoucherGrabber dataGrabber) {
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
        AuditTrailService.logAuditEvent("SERVICE_BATCH_CYCLE", "JournalPostingService", batchName, "RECORDS=" + recordCount);
        TelemetryRecorder.recordMetric("JournalPostingService." + batchName + ".records", (double) recordCount);
    }

    public JournalVoucher inspectAndReconcile(String entityId) {
        JournalVoucher entity = this.dataGrabber.fetchJournalVoucherById(entityId);
        if (entity != null) {
            entity.Modify("RECONCILED");
        }
        return entity;
    }
}
