package com.tcs.bancs.GL;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Process Before Batch (Pre-run validation): GLPBPreBalancingAudit
 */
public class GLPBPreBalancingAudit {

    private final GLDGLedgerGrabber dataGrabber;
    private final GeneralLedgerService service;
    private boolean isExecutionRunning = false;

    public GLPBPreBalancingAudit() {
        this.dataGrabber = new GLDGLedgerGrabber();
        this.service = new GeneralLedgerService();
    }

    public GLPBPreBalancingAudit(GLDGLedgerGrabber dataGrabber, GeneralLedgerService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary batch execution method: GLPBPreBalancingAuditProcess
     */
    public synchronized int GLPBPreBalancingAuditProcess() {
        this.isExecutionRunning = true;
        int processedRecords = 0;
        try {
            AuditTrailService.logAuditEvent("BATCH_START", "GLPBPreBalancingAudit", "PB", "START");
            List<?> records = this.dataGrabber.retrieveAll();
            processedRecords = records != null ? records.size() : 0;
            this.service.executeBatchProcessingCycle("GLPBPreBalancingAudit", processedRecords);
            AuditTrailService.logAuditEvent("BATCH_COMPLETE", "GLPBPreBalancingAudit", "PB", "PROCESSED=" + processedRecords);
        } finally {
            this.isExecutionRunning = false;
        }
        return processedRecords;
    }

    public boolean isRunning() {
        return this.isExecutionRunning;
    }
}
