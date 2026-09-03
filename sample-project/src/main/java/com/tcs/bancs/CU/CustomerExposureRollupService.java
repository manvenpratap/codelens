package com.tcs.bancs.CU;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Core Domain Service: CustomerExposureRollupService
 * Implements business calculation logic, validations, and domain rules.
 */
public class CustomerExposureRollupService {

    private final CUDGCustomerGrabber dataGrabber;

    public CustomerExposureRollupService() {
        this.dataGrabber = new CUDGCustomerGrabber();
    }

    public CustomerExposureRollupService(CUDGCustomerGrabber dataGrabber) {
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
        AuditTrailService.logAuditEvent("SERVICE_BATCH_CYCLE", "CustomerExposureRollupService", batchName, "RECORDS=" + recordCount);
        TelemetryRecorder.recordMetric("CustomerExposureRollupService." + batchName + ".records", (double) recordCount);
    }

    public CustomerProfile inspectAndReconcile(String entityId) {
        CustomerProfile entity = this.dataGrabber.fetchCustomerProfileById(entityId);
        if (entity != null) {
            entity.Modify("RECONCILED");
        }
        return entity;
    }
}
