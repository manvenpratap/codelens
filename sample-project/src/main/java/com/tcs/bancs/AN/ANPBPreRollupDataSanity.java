package com.tcs.bancs.AN;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Process Before Batch (Pre-run validation): ANPBPreRollupDataSanity
 */
public class ANPBPreRollupDataSanity {

    private final ANDGAnalyticsGrabber dataGrabber;
    private final PnLCalculationService service;
    private boolean isExecutionRunning = false;

    public ANPBPreRollupDataSanity() {
        this.dataGrabber = new ANDGAnalyticsGrabber();
        this.service = new PnLCalculationService();
    }

    public ANPBPreRollupDataSanity(ANDGAnalyticsGrabber dataGrabber, PnLCalculationService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary batch execution method: ANPBPreRollupDataSanityProcess
     */
    public synchronized int ANPBPreRollupDataSanityProcess() {
        this.isExecutionRunning = true;
        int processedRecords = 0;
        try {
            AuditTrailService.logAuditEvent("BATCH_START", "ANPBPreRollupDataSanity", "PB", "START");
            List<?> records = this.dataGrabber.retrieveAll();
            processedRecords = records != null ? records.size() : 0;
            this.service.executeBatchProcessingCycle("ANPBPreRollupDataSanity", processedRecords);
            AuditTrailService.logAuditEvent("BATCH_COMPLETE", "ANPBPreRollupDataSanity", "PB", "PROCESSED=" + processedRecords);
        } finally {
            this.isExecutionRunning = false;
        }
        return processedRecords;
    }

    public boolean isRunning() {
        return this.isExecutionRunning;
    }
}
