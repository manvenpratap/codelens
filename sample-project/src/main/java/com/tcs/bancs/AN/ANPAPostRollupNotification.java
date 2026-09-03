package com.tcs.bancs.AN;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Process After Batch (Post-run reconciliation): ANPAPostRollupNotification
 */
public class ANPAPostRollupNotification {

    private final ANDGAnalyticsGrabber dataGrabber;
    private final PnLCalculationService service;
    private boolean isExecutionRunning = false;

    public ANPAPostRollupNotification() {
        this.dataGrabber = new ANDGAnalyticsGrabber();
        this.service = new PnLCalculationService();
    }

    public ANPAPostRollupNotification(ANDGAnalyticsGrabber dataGrabber, PnLCalculationService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary batch execution method: ANPAPostRollupNotificationProcess
     */
    public synchronized int ANPAPostRollupNotificationProcess() {
        this.isExecutionRunning = true;
        int processedRecords = 0;
        try {
            AuditTrailService.logAuditEvent("BATCH_START", "ANPAPostRollupNotification", "PA", "START");
            List<?> records = this.dataGrabber.retrieveAll();
            processedRecords = records != null ? records.size() : 0;
            this.service.executeBatchProcessingCycle("ANPAPostRollupNotification", processedRecords);
            AuditTrailService.logAuditEvent("BATCH_COMPLETE", "ANPAPostRollupNotification", "PA", "PROCESSED=" + processedRecords);
        } finally {
            this.isExecutionRunning = false;
        }
        return processedRecords;
    }

    public boolean isRunning() {
        return this.isExecutionRunning;
    }
}
