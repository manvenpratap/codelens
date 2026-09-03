package com.tcs.bancs.MS;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Batch Processor (EOD / Periodic Execution): MSPSEODQueueArchive
 */
public class MSPSEODQueueArchive {

    private final MSDGMessageGrabber dataGrabber;
    private final SwiftParserService service;
    private boolean isExecutionRunning = false;

    public MSPSEODQueueArchive() {
        this.dataGrabber = new MSDGMessageGrabber();
        this.service = new SwiftParserService();
    }

    public MSPSEODQueueArchive(MSDGMessageGrabber dataGrabber, SwiftParserService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary batch execution method: MSPSEODQueueArchiveProcess
     */
    public synchronized int MSPSEODQueueArchiveProcess() {
        this.isExecutionRunning = true;
        int processedRecords = 0;
        try {
            AuditTrailService.logAuditEvent("BATCH_START", "MSPSEODQueueArchive", "PS", "START");
            List<?> records = this.dataGrabber.retrieveAll();
            processedRecords = records != null ? records.size() : 0;
            this.service.executeBatchProcessingCycle("MSPSEODQueueArchive", processedRecords);
            AuditTrailService.logAuditEvent("BATCH_COMPLETE", "MSPSEODQueueArchive", "PS", "PROCESSED=" + processedRecords);
        } finally {
            this.isExecutionRunning = false;
        }
        return processedRecords;
    }

    public boolean isRunning() {
        return this.isExecutionRunning;
    }
}
