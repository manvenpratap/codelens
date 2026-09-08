package com.tcs.bancs.GL;

import java.util.*;
import com.tcs.bancs.common.*;
import com.tcs.bancs.AM.*;
import com.tcs.bancs.LN.*;
import com.tcs.bancs.TR.*;
import com.tcs.bancs.CL.*;
import com.tcs.bancs.PM.*;

/**
 * TCS BaNCS Core Domain Service: PeriodClosureService
 * Implements transaction methods (ET/BT), tasks (TO/TC), and domain calculations.
 */
public class PeriodClosureService {

    private final GLDGPeriodGrabber dataGrabber;

    public PeriodClosureService() {
        this.dataGrabber = new GLDGPeriodGrabber();
    }

    public PeriodClosureService(GLDGPeriodGrabber dataGrabber) {
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
        AuditTrailService.logAuditEvent("SERVICE_BATCH_CYCLE", "PeriodClosureService", batchName, "RECORDS=" + recordCount);
        TelemetryRecorder.recordMetric("PeriodClosureService." + batchName + ".records", (double) recordCount);
    }

    public FinancialPeriod inspectAndReconcile(String entityId) {
        FinancialPeriod entity = this.dataGrabber.fetchFinancialPeriodById(entityId);
        if (entity != null) {
            entity.Modify("RECONCILED");
        }
        return entity;
    }

    /**
     * TCS BaNCS Own Task delegate: GLTOPostPostingLeg
     */
    public boolean GLTOPostPostingLeg(String correlationId) {
        AuditTrailService.logAuditEvent("TASK_OWN", "GLTOPostPostingLeg", correlationId, "SECONDARY_SERVICE");
        return this.dataGrabber != null && this.dataGrabber.exists(correlationId);
    }

    /**
     * TCS BaNCS Common Task delegate: GLTCAuditTransaction
     */
    public void GLTCAuditTransaction(String operation, String correlationId) {
        AuditTrailService.logAuditEvent("TASK_COMMON", "GLTCAuditTransaction", correlationId, operation);
    }
}
