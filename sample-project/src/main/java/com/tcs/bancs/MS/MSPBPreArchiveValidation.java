package com.tcs.bancs.MS;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Process Before Batch (Pre-run validation): MSPBPreArchiveValidation
 */
public class MSPBPreArchiveValidation {

    private final MSDGMessageGrabber dataGrabber;
    private final SwiftParserService service;
    private boolean isExecutionRunning = false;

    public MSPBPreArchiveValidation() {
        this.dataGrabber = new MSDGMessageGrabber();
        this.service = new SwiftParserService();
    }

    public MSPBPreArchiveValidation(MSDGMessageGrabber dataGrabber, SwiftParserService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary batch execution method: MSPBPreArchiveValidationProcess
     */
    public synchronized int MSPBPreArchiveValidationProcess() {
        this.isExecutionRunning = true;
        int processedRecords = 0;
        try {
            AuditTrailService.logAuditEvent("BATCH_START", "MSPBPreArchiveValidation", "PB", "START");
            List<?> records = this.dataGrabber.retrieveAll();
            processedRecords = records != null ? records.size() : 0;
            this.service.executeBatchProcessingCycle("MSPBPreArchiveValidation", processedRecords);
            AuditTrailService.logAuditEvent("BATCH_COMPLETE", "MSPBPreArchiveValidation", "PB", "PROCESSED=" + processedRecords);
        } finally {
            this.isExecutionRunning = false;
        }
        return processedRecords;
    }

    public boolean isRunning() {
        return this.isExecutionRunning;
    }
}
