package com.tcs.bancs.LN;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Batch Processor (EOD / Periodic Execution): LNPSInstallmentDueBatch
 */
public class LNPSInstallmentDueBatch {

    private final LNDGLoanGrabber dataGrabber;
    private final LoanOriginationService service;
    private boolean isExecutionRunning = false;

    public LNPSInstallmentDueBatch() {
        this.dataGrabber = new LNDGLoanGrabber();
        this.service = new LoanOriginationService();
    }

    public LNPSInstallmentDueBatch(LNDGLoanGrabber dataGrabber, LoanOriginationService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary batch execution method: LNPSInstallmentDueBatchProcess
     */
    public synchronized int LNPSInstallmentDueBatchProcess() {
        this.isExecutionRunning = true;
        int processedRecords = 0;
        try {
            AuditTrailService.logAuditEvent("BATCH_START", "LNPSInstallmentDueBatch", "PS", "START");
            List<?> records = this.dataGrabber.retrieveAll();
            processedRecords = records != null ? records.size() : 0;
            this.service.executeBatchProcessingCycle("LNPSInstallmentDueBatch", processedRecords);
            AuditTrailService.logAuditEvent("BATCH_COMPLETE", "LNPSInstallmentDueBatch", "PS", "PROCESSED=" + processedRecords);
        } finally {
            this.isExecutionRunning = false;
        }
        return processedRecords;
    }

    public boolean isRunning() {
        return this.isExecutionRunning;
    }
}
