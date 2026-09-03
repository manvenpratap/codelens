package com.tcs.bancs.AN;

import java.util.*;
import com.tcs.bancs.common.*;
import com.tcs.bancs.AM.*;

/**
 * TCS BaNCS Business Transaction: ANBTCalculatePnL
 * Orchestrates multi-step transactional workflows, entity mutation, and ledger updates.
 */
public class ANBTCalculatePnL {

    private final ANDGAnalyticsGrabber dataGrabber;
    private final PnLCalculationService service;

    public ANBTCalculatePnL() {
        this.dataGrabber = new ANDGAnalyticsGrabber();
        this.service = new PnLCalculationService();
    }

    public ANBTCalculatePnL(ANDGAnalyticsGrabber dataGrabber, PnLCalculationService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary BaNCS Business Transaction entry point: ANBTCalculatePnLExecute
     */
    public MO_OUT_PnLCalculation ANBTCalculatePnLExecute(MO_INP_PnLCalculation req) {
        if (req == null) {
            throw new ValidationException("Input message object cannot be null in " + "ANBTCalculatePnL");
        }

        // Step 1: Pre-transaction validation via service
        boolean isValid = this.service.validateTransactionPreconditions(req.getMessageCorrelationId());
        if (!isValid) {
            throw new BusinessException("Validation failed in " + "ANBTCalculatePnL");
        }

        // Step 2: Data Grabber state query
        PnLSummaryRecord entity = this.dataGrabber.fetchPnLSummaryRecordById(req.getMessageCorrelationId());

        // Step 3: Domain Entity Mutation
        if (entity != null) {
            entity.Create();
            entity.Modify("EXECUTED");
        }

        // Cross-module integration: AN -> AM
        AuditTrailService.logAuditEvent("CROSS_MODULE_CALL", "ANBTCalculatePnL", req.getMessageCorrelationId(), "AM");

        // Step 4: Audit & Telemetry
        AuditTrailService.logAuditEvent("BUSINESS_TRANSACTION", "ANBTCalculatePnL", req.getMessageCorrelationId(), "SUCCESS");
        TelemetryRecorder.recordMetric("ANBTCalculatePnL.execution.count", 1.0);

        // Step 5: Construct and return Output Message Object
        MO_OUT_PnLCalculation resp = new MO_OUT_PnLCalculation();
        resp.setMessageCorrelationId(req.getMessageCorrelationId());
        return resp;
    }

    public boolean checkTransactionEligibility(String correlationId) {
        return this.dataGrabber.exists(correlationId);
    }
}
