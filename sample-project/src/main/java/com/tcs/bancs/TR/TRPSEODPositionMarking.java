package com.tcs.bancs.TR;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Batch Processor (EOD / Periodic Execution): TRPSEODPositionMarking
 */
public class TRPSEODPositionMarking {

    private final TRDGTradeGrabber dataGrabber;
    private final OrderRoutingService service;
    private boolean isExecutionRunning = false;

    public TRPSEODPositionMarking() {
        this.dataGrabber = new TRDGTradeGrabber();
        this.service = new OrderRoutingService();
    }

    public TRPSEODPositionMarking(TRDGTradeGrabber dataGrabber, OrderRoutingService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary batch execution method: TRPSEODPositionMarkingProcess
     */
    public synchronized int TRPSEODPositionMarkingProcess() {
        this.isExecutionRunning = true;
        int processedRecords = 0;
        try {
            AuditTrailService.logAuditEvent("BATCH_START", "TRPSEODPositionMarking", "PS", "START");
            List<?> records = this.dataGrabber.retrieveAll();
            processedRecords = records != null ? records.size() : 0;
            this.service.executeBatchProcessingCycle("TRPSEODPositionMarking", processedRecords);
            AuditTrailService.logAuditEvent("BATCH_COMPLETE", "TRPSEODPositionMarking", "PS", "PROCESSED=" + processedRecords);
        } finally {
            this.isExecutionRunning = false;
        }
        return processedRecords;
    }

    public boolean isRunning() {
        return this.isExecutionRunning;
    }
}
