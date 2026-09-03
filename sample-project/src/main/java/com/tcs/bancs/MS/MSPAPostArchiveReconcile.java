package com.tcs.bancs.MS;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Process After Batch (Post-run reconciliation): MSPAPostArchiveReconcile
 */
public class MSPAPostArchiveReconcile {

    private final MSDGMessageGrabber dataGrabber;
    private final SwiftParserService service;
    private boolean isExecutionRunning = false;

    public MSPAPostArchiveReconcile() {
        this.dataGrabber = new MSDGMessageGrabber();
        this.service = new SwiftParserService();
    }

    public MSPAPostArchiveReconcile(MSDGMessageGrabber dataGrabber, SwiftParserService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary batch execution method: MSPAPostArchiveReconcileProcess
     */
    public synchronized int MSPAPostArchiveReconcileProcess() {
        this.isExecutionRunning = true;
        int processedRecords = 0;
        try {
            AuditTrailService.logAuditEvent("BATCH_START", "MSPAPostArchiveReconcile", "PA", "START");
            List<?> records = this.dataGrabber.retrieveAll();
            processedRecords = records != null ? records.size() : 0;
            this.service.executeBatchProcessingCycle("MSPAPostArchiveReconcile", processedRecords);
            AuditTrailService.logAuditEvent("BATCH_COMPLETE", "MSPAPostArchiveReconcile", "PA", "PROCESSED=" + processedRecords);
        } finally {
            this.isExecutionRunning = false;
        }
        return processedRecords;
    }

    public boolean isRunning() {
        return this.isExecutionRunning;
    }
}
