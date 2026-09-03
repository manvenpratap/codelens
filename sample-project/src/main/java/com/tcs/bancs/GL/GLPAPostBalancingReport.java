package com.tcs.bancs.GL;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Process After Batch (Post-run reconciliation): GLPAPostBalancingReport
 */
public class GLPAPostBalancingReport {

    private final GLDGLedgerGrabber dataGrabber;
    private final GeneralLedgerService service;
    private boolean isExecutionRunning = false;

    public GLPAPostBalancingReport() {
        this.dataGrabber = new GLDGLedgerGrabber();
        this.service = new GeneralLedgerService();
    }

    public GLPAPostBalancingReport(GLDGLedgerGrabber dataGrabber, GeneralLedgerService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary batch execution method: GLPAPostBalancingReportProcess
     */
    public synchronized int GLPAPostBalancingReportProcess() {
        this.isExecutionRunning = true;
        int processedRecords = 0;
        try {
            AuditTrailService.logAuditEvent("BATCH_START", "GLPAPostBalancingReport", "PA", "START");
            List<?> records = this.dataGrabber.retrieveAll();
            processedRecords = records != null ? records.size() : 0;
            this.service.executeBatchProcessingCycle("GLPAPostBalancingReport", processedRecords);
            AuditTrailService.logAuditEvent("BATCH_COMPLETE", "GLPAPostBalancingReport", "PA", "PROCESSED=" + processedRecords);
        } finally {
            this.isExecutionRunning = false;
        }
        return processedRecords;
    }

    public boolean isRunning() {
        return this.isExecutionRunning;
    }
}
