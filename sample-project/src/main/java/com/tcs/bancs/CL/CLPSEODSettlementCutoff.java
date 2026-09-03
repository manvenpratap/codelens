package com.tcs.bancs.CL;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Batch Processor (EOD / Periodic Execution): CLPSEODSettlementCutoff
 */
public class CLPSEODSettlementCutoff {

    private final CLDGSettlementGrabber dataGrabber;
    private final ClearingHouseGatewayService service;
    private boolean isExecutionRunning = false;

    public CLPSEODSettlementCutoff() {
        this.dataGrabber = new CLDGSettlementGrabber();
        this.service = new ClearingHouseGatewayService();
    }

    public CLPSEODSettlementCutoff(CLDGSettlementGrabber dataGrabber, ClearingHouseGatewayService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary batch execution method: CLPSEODSettlementCutoffProcess
     */
    public synchronized int CLPSEODSettlementCutoffProcess() {
        this.isExecutionRunning = true;
        int processedRecords = 0;
        try {
            AuditTrailService.logAuditEvent("BATCH_START", "CLPSEODSettlementCutoff", "PS", "START");
            List<?> records = this.dataGrabber.retrieveAll();
            processedRecords = records != null ? records.size() : 0;
            this.service.executeBatchProcessingCycle("CLPSEODSettlementCutoff", processedRecords);
            AuditTrailService.logAuditEvent("BATCH_COMPLETE", "CLPSEODSettlementCutoff", "PS", "PROCESSED=" + processedRecords);
        } finally {
            this.isExecutionRunning = false;
        }
        return processedRecords;
    }

    public boolean isRunning() {
        return this.isExecutionRunning;
    }
}
