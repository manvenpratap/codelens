package com.tcs.bancs.LN;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Process Before Batch (Pre-run validation): LNPBPreDueValidation
 */
public class LNPBPreDueValidation {

    private final LNDGLoanGrabber dataGrabber;
    private final LoanOriginationService service;
    private boolean isExecutionRunning = false;

    public LNPBPreDueValidation() {
        this.dataGrabber = new LNDGLoanGrabber();
        this.service = new LoanOriginationService();
    }

    public LNPBPreDueValidation(LNDGLoanGrabber dataGrabber, LoanOriginationService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary batch execution method: LNPBPreDueValidationProcess
     */
    public synchronized int LNPBPreDueValidationProcess() {
        this.isExecutionRunning = true;
        int processedRecords = 0;
        try {
            AuditTrailService.logAuditEvent("BATCH_START", "LNPBPreDueValidation", "PB", "START");
            List<?> records = this.dataGrabber.retrieveAll();
            processedRecords = records != null ? records.size() : 0;
            this.service.executeBatchProcessingCycle("LNPBPreDueValidation", processedRecords);
            AuditTrailService.logAuditEvent("BATCH_COMPLETE", "LNPBPreDueValidation", "PB", "PROCESSED=" + processedRecords);
        } finally {
            this.isExecutionRunning = false;
        }
        return processedRecords;
    }

    public boolean isRunning() {
        return this.isExecutionRunning;
    }
}
