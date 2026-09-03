package com.tcs.bancs.LN;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Process After Batch (Post-run reconciliation): LNPAPostPaymentReconcile
 */
public class LNPAPostPaymentReconcile {

    private final LNDGLoanGrabber dataGrabber;
    private final LoanOriginationService service;
    private boolean isExecutionRunning = false;

    public LNPAPostPaymentReconcile() {
        this.dataGrabber = new LNDGLoanGrabber();
        this.service = new LoanOriginationService();
    }

    public LNPAPostPaymentReconcile(LNDGLoanGrabber dataGrabber, LoanOriginationService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary batch execution method: LNPAPostPaymentReconcileProcess
     */
    public synchronized int LNPAPostPaymentReconcileProcess() {
        this.isExecutionRunning = true;
        int processedRecords = 0;
        try {
            AuditTrailService.logAuditEvent("BATCH_START", "LNPAPostPaymentReconcile", "PA", "START");
            List<?> records = this.dataGrabber.retrieveAll();
            processedRecords = records != null ? records.size() : 0;
            this.service.executeBatchProcessingCycle("LNPAPostPaymentReconcile", processedRecords);
            AuditTrailService.logAuditEvent("BATCH_COMPLETE", "LNPAPostPaymentReconcile", "PA", "PROCESSED=" + processedRecords);
        } finally {
            this.isExecutionRunning = false;
        }
        return processedRecords;
    }

    public boolean isRunning() {
        return this.isExecutionRunning;
    }
}
