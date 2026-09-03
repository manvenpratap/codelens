package com.tcs.bancs.RK;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Batch Processor (EOD / Periodic Execution): RKPSEODRiskComputation
 */
public class RKPSEODRiskComputation {

    private final RKDGRiskGrabber dataGrabber;
    private final MarketRiskService service;
    private boolean isExecutionRunning = false;

    public RKPSEODRiskComputation() {
        this.dataGrabber = new RKDGRiskGrabber();
        this.service = new MarketRiskService();
    }

    public RKPSEODRiskComputation(RKDGRiskGrabber dataGrabber, MarketRiskService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary batch execution method: RKPSEODRiskComputationProcess
     */
    public synchronized int RKPSEODRiskComputationProcess() {
        this.isExecutionRunning = true;
        int processedRecords = 0;
        try {
            AuditTrailService.logAuditEvent("BATCH_START", "RKPSEODRiskComputation", "PS", "START");
            List<?> records = this.dataGrabber.retrieveAll();
            processedRecords = records != null ? records.size() : 0;
            this.service.executeBatchProcessingCycle("RKPSEODRiskComputation", processedRecords);
            AuditTrailService.logAuditEvent("BATCH_COMPLETE", "RKPSEODRiskComputation", "PS", "PROCESSED=" + processedRecords);
        } finally {
            this.isExecutionRunning = false;
        }
        return processedRecords;
    }

    public boolean isRunning() {
        return this.isExecutionRunning;
    }
}
