package com.tcs.bancs.AN;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Batch Processor (EOD / Periodic Execution): ANPSEODAnalyticsRollup
 */
public class ANPSEODAnalyticsRollup {

    private final ANDGAnalyticsGrabber dataGrabber;
    private final PnLCalculationService service;
    private boolean isExecutionRunning = false;

    public ANPSEODAnalyticsRollup() {
        this.dataGrabber = new ANDGAnalyticsGrabber();
        this.service = new PnLCalculationService();
    }

    public ANPSEODAnalyticsRollup(ANDGAnalyticsGrabber dataGrabber, PnLCalculationService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary batch execution method: ANPSEODAnalyticsRollupProcess
     */
    public synchronized int ANPSEODAnalyticsRollupProcess() {
        this.isExecutionRunning = true;
        int processedRecords = 0;
        try {
            AuditTrailService.logAuditEvent("BATCH_START", "ANPSEODAnalyticsRollup", "PS", "START");
            List<?> records = this.dataGrabber.retrieveAll();
            processedRecords = records != null ? records.size() : 0;
            this.service.executeBatchProcessingCycle("ANPSEODAnalyticsRollup", processedRecords);
            AuditTrailService.logAuditEvent("BATCH_COMPLETE", "ANPSEODAnalyticsRollup", "PS", "PROCESSED=" + processedRecords);
        } finally {
            this.isExecutionRunning = false;
        }
        return processedRecords;
    }

    public boolean isRunning() {
        return this.isExecutionRunning;
    }
}
