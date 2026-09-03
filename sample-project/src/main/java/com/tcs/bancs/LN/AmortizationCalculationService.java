package com.tcs.bancs.LN;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Core Domain Service: AmortizationCalculationService
 * Implements business calculation logic, validations, and domain rules.
 */
public class AmortizationCalculationService {

    private final LNDGScheduleGrabber dataGrabber;

    public AmortizationCalculationService() {
        this.dataGrabber = new LNDGScheduleGrabber();
    }

    public AmortizationCalculationService(LNDGScheduleGrabber dataGrabber) {
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
        AuditTrailService.logAuditEvent("SERVICE_BATCH_CYCLE", "AmortizationCalculationService", batchName, "RECORDS=" + recordCount);
        TelemetryRecorder.recordMetric("AmortizationCalculationService." + batchName + ".records", (double) recordCount);
    }

    public LoanRepaymentSchedule inspectAndReconcile(String entityId) {
        LoanRepaymentSchedule entity = this.dataGrabber.fetchLoanRepaymentScheduleById(entityId);
        if (entity != null) {
            entity.Modify("RECONCILED");
        }
        return entity;
    }
}
