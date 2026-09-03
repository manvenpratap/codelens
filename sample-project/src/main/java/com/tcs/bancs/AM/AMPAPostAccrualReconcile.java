package com.tcs.bancs.AM;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Process After Batch (Post-run reconciliation): AMPAPostAccrualReconcile
 */
public class AMPAPostAccrualReconcile {

    private final AMDGAccountGrabber dataGrabber;
    private final AccountService service;
    private boolean isExecutionRunning = false;

    public AMPAPostAccrualReconcile() {
        this.dataGrabber = new AMDGAccountGrabber();
        this.service = new AccountService();
    }

    public AMPAPostAccrualReconcile(AMDGAccountGrabber dataGrabber, AccountService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary batch execution method: AMPAPostAccrualReconcileProcess
     */
    public synchronized int AMPAPostAccrualReconcileProcess() {
        this.isExecutionRunning = true;
        int processedRecords = 0;
        try {
            AuditTrailService.logAuditEvent("BATCH_START", "AMPAPostAccrualReconcile", "PA", "START");
            List<?> records = this.dataGrabber.retrieveAll();
            processedRecords = records != null ? records.size() : 0;
            this.service.executeBatchProcessingCycle("AMPAPostAccrualReconcile", processedRecords);
            AuditTrailService.logAuditEvent("BATCH_COMPLETE", "AMPAPostAccrualReconcile", "PA", "PROCESSED=" + processedRecords);
        } finally {
            this.isExecutionRunning = false;
        }
        return processedRecords;
    }

    public boolean isRunning() {
        return this.isExecutionRunning;
    }
}
