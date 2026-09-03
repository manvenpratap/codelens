package com.tcs.bancs.RK;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Process Before Batch (Pre-run validation): RKPBPreRiskDataCollection
 */
public class RKPBPreRiskDataCollection {

    private final RKDGRiskGrabber dataGrabber;
    private final MarketRiskService service;
    private boolean isExecutionRunning = false;

    public RKPBPreRiskDataCollection() {
        this.dataGrabber = new RKDGRiskGrabber();
        this.service = new MarketRiskService();
    }

    public RKPBPreRiskDataCollection(RKDGRiskGrabber dataGrabber, MarketRiskService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary batch execution method: RKPBPreRiskDataCollectionProcess
     */
    public synchronized int RKPBPreRiskDataCollectionProcess() {
        this.isExecutionRunning = true;
        int processedRecords = 0;
        try {
            AuditTrailService.logAuditEvent("BATCH_START", "RKPBPreRiskDataCollection", "PB", "START");
            List<?> records = this.dataGrabber.retrieveAll();
            processedRecords = records != null ? records.size() : 0;
            this.service.executeBatchProcessingCycle("RKPBPreRiskDataCollection", processedRecords);
            AuditTrailService.logAuditEvent("BATCH_COMPLETE", "RKPBPreRiskDataCollection", "PB", "PROCESSED=" + processedRecords);
        } finally {
            this.isExecutionRunning = false;
        }
        return processedRecords;
    }

    public boolean isRunning() {
        return this.isExecutionRunning;
    }
}
