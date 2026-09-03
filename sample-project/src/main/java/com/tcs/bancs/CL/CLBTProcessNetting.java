package com.tcs.bancs.CL;

import java.util.*;
import com.tcs.bancs.common.*;
import com.tcs.bancs.GL.*;

/**
 * TCS BaNCS Business Transaction: CLBTProcessNetting
 * Orchestrates multi-step transactional workflows, entity mutation, and ledger updates.
 */
public class CLBTProcessNetting {

    private final CLDGNettingGrabber dataGrabber;
    private final NettingCalculationService service;

    public CLBTProcessNetting() {
        this.dataGrabber = new CLDGNettingGrabber();
        this.service = new NettingCalculationService();
    }

    public CLBTProcessNetting(CLDGNettingGrabber dataGrabber, NettingCalculationService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary BaNCS Business Transaction entry point: CLBTProcessNettingExecute
     */
    public MO_OUT_NettingRequest CLBTProcessNettingExecute(MO_INP_NettingRequest req) {
        if (req == null) {
            throw new ValidationException("Input message object cannot be null in " + "CLBTProcessNetting");
        }

        // Step 1: Pre-transaction validation via service
        boolean isValid = this.service.validateTransactionPreconditions(req.getMessageCorrelationId());
        if (!isValid) {
            throw new BusinessException("Validation failed in " + "CLBTProcessNetting");
        }

        // Step 2: Data Grabber state query
        NettingBatch entity = this.dataGrabber.fetchNettingBatchById(req.getMessageCorrelationId());

        // Step 3: Domain Entity Mutation
        if (entity != null) {
            entity.Create();
            entity.Modify("EXECUTED");
        }

        // Cross-module integration: CL -> GL
        AuditTrailService.logAuditEvent("CROSS_MODULE_CALL", "CLBTProcessNetting", req.getMessageCorrelationId(), "GL");

        // Step 4: Audit & Telemetry
        AuditTrailService.logAuditEvent("BUSINESS_TRANSACTION", "CLBTProcessNetting", req.getMessageCorrelationId(), "SUCCESS");
        TelemetryRecorder.recordMetric("CLBTProcessNetting.execution.count", 1.0);

        // Step 5: Construct and return Output Message Object
        MO_OUT_NettingRequest resp = new MO_OUT_NettingRequest();
        resp.setMessageCorrelationId(req.getMessageCorrelationId());
        return resp;
    }

    public boolean checkTransactionEligibility(String correlationId) {
        return this.dataGrabber.exists(correlationId);
    }
}
