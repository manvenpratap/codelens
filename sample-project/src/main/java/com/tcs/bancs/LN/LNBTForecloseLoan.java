package com.tcs.bancs.LN;

import java.util.*;
import com.tcs.bancs.common.*;
import com.tcs.bancs.AM.*;

/**
 * TCS BaNCS Business Transaction: LNBTForecloseLoan
 * Orchestrates multi-step transactional workflows, entity mutation, and ledger updates.
 */
public class LNBTForecloseLoan {

    private final LNDGDisbursementGrabber dataGrabber;
    private final DelinquencyTrackingService service;

    public LNBTForecloseLoan() {
        this.dataGrabber = new LNDGDisbursementGrabber();
        this.service = new DelinquencyTrackingService();
    }

    public LNBTForecloseLoan(LNDGDisbursementGrabber dataGrabber, DelinquencyTrackingService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary BaNCS Business Transaction entry point: LNBTForecloseLoanExecute
     */
    public MO_OUT_ScheduleRestructure LNBTForecloseLoanExecute(MO_INP_ScheduleRestructure req) {
        if (req == null) {
            throw new ValidationException("Input message object cannot be null in " + "LNBTForecloseLoan");
        }

        // Step 1: Pre-transaction validation via service
        boolean isValid = this.service.validateTransactionPreconditions(req.getMessageCorrelationId());
        if (!isValid) {
            throw new BusinessException("Validation failed in " + "LNBTForecloseLoan");
        }

        // Step 2: Data Grabber state query
        DelinquencyRecord entity = this.dataGrabber.fetchDelinquencyRecordById(req.getMessageCorrelationId());

        // Step 3: Domain Entity Mutation
        if (entity != null) {
            entity.Create();
            entity.Modify("EXECUTED");
        }

        // Cross-module integration: LN -> AM
        AuditTrailService.logAuditEvent("CROSS_MODULE_CALL", "LNBTForecloseLoan", req.getMessageCorrelationId(), "AM");

        // Step 4: Audit & Telemetry
        AuditTrailService.logAuditEvent("BUSINESS_TRANSACTION", "LNBTForecloseLoan", req.getMessageCorrelationId(), "SUCCESS");
        TelemetryRecorder.recordMetric("LNBTForecloseLoan.execution.count", 1.0);

        // Step 5: Construct and return Output Message Object
        MO_OUT_ScheduleRestructure resp = new MO_OUT_ScheduleRestructure();
        resp.setMessageCorrelationId(req.getMessageCorrelationId());
        return resp;
    }

    public boolean checkTransactionEligibility(String correlationId) {
        return this.dataGrabber.exists(correlationId);
    }
}
