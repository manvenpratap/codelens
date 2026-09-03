package com.tcs.bancs.PM;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Process After Batch (Post-run reconciliation): PMPAPostClearingReconcile
 */
public class PMPAPostClearingReconcile {

    private final PMDGPaymentGrabber dataGrabber;
    private final PaymentInitiationService service;
    private boolean isExecutionRunning = false;

    public PMPAPostClearingReconcile() {
        this.dataGrabber = new PMDGPaymentGrabber();
        this.service = new PaymentInitiationService();
    }

    public PMPAPostClearingReconcile(PMDGPaymentGrabber dataGrabber, PaymentInitiationService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary batch execution method: PMPAPostClearingReconcileProcess
     */
    public synchronized int PMPAPostClearingReconcileProcess() {
        this.isExecutionRunning = true;
        int processedRecords = 0;
        try {
            AuditTrailService.logAuditEvent("BATCH_START", "PMPAPostClearingReconcile", "PA", "START");
            List<?> records = this.dataGrabber.retrieveAll();
            processedRecords = records != null ? records.size() : 0;
            this.service.executeBatchProcessingCycle("PMPAPostClearingReconcile", processedRecords);
            AuditTrailService.logAuditEvent("BATCH_COMPLETE", "PMPAPostClearingReconcile", "PA", "PROCESSED=" + processedRecords);
        } finally {
            this.isExecutionRunning = false;
        }
        return processedRecords;
    }

    public boolean isRunning() {
        return this.isExecutionRunning;
    }
}
