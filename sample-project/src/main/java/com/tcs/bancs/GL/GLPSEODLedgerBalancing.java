package com.tcs.bancs.GL;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Batch Processor (EOD / Periodic Execution): GLPSEODLedgerBalancing
 */
public class GLPSEODLedgerBalancing {

    private final GLDGLedgerGrabber dataGrabber;
    private final GeneralLedgerService service;
    private boolean isExecutionRunning = false;

    public GLPSEODLedgerBalancing() {
        this.dataGrabber = new GLDGLedgerGrabber();
        this.service = new GeneralLedgerService();
    }

    public GLPSEODLedgerBalancing(GLDGLedgerGrabber dataGrabber, GeneralLedgerService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary batch execution method: GLPSEODLedgerBalancingProcess
     */
    public synchronized int GLPSEODLedgerBalancingProcess() {
        this.isExecutionRunning = true;
        int processedRecords = 0;
        try {
            AuditTrailService.logAuditEvent("BATCH_START", "GLPSEODLedgerBalancing", "PS", "START");
            List<?> records = this.dataGrabber.retrieveAll();
            processedRecords = records != null ? records.size() : 0;
            this.service.executeBatchProcessingCycle("GLPSEODLedgerBalancing", processedRecords);
            AuditTrailService.logAuditEvent("BATCH_COMPLETE", "GLPSEODLedgerBalancing", "PS", "PROCESSED=" + processedRecords);
        } finally {
            this.isExecutionRunning = false;
        }
        return processedRecords;
    }

    public boolean isRunning() {
        return this.isExecutionRunning;
    }
}
