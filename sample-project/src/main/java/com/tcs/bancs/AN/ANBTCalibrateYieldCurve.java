package com.tcs.bancs.AN;

import java.util.*;
import com.tcs.bancs.common.*;
import com.tcs.bancs.AM.*;

/**
 * TCS BaNCS Business Transaction: ANBTCalibrateYieldCurve
 * Orchestrates multi-step transactional workflows, entity mutation, and ledger updates.
 */
public class ANBTCalibrateYieldCurve {

    private final ANDGYieldCurveGrabber dataGrabber;
    private final YieldCurveBootstrappingService service;

    public ANBTCalibrateYieldCurve() {
        this.dataGrabber = new ANDGYieldCurveGrabber();
        this.service = new YieldCurveBootstrappingService();
    }

    public ANBTCalibrateYieldCurve(ANDGYieldCurveGrabber dataGrabber, YieldCurveBootstrappingService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary BaNCS Business Transaction entry point: ANBTCalibrateYieldCurveExecute
     */
    public MO_OUT_YieldCurveQuery ANBTCalibrateYieldCurveExecute(MO_INP_YieldCurveQuery req) {
        if (req == null) {
            throw new ValidationException("Input message object cannot be null in " + "ANBTCalibrateYieldCurve");
        }

        // Step 1: Pre-transaction validation via service
        boolean isValid = this.service.validateTransactionPreconditions(req.getMessageCorrelationId());
        if (!isValid) {
            throw new BusinessException("Validation failed in " + "ANBTCalibrateYieldCurve");
        }

        // Step 2: Data Grabber state query
        YieldCurveSnapshot entity = this.dataGrabber.fetchYieldCurveSnapshotById(req.getMessageCorrelationId());

        // Step 3: Domain Entity Mutation
        if (entity != null) {
            entity.Create();
            entity.Modify("EXECUTED");
        }

        // Cross-module integration: AN -> AM
        AuditTrailService.logAuditEvent("CROSS_MODULE_CALL", "ANBTCalibrateYieldCurve", req.getMessageCorrelationId(), "AM");

        // Step 4: Audit & Telemetry
        AuditTrailService.logAuditEvent("BUSINESS_TRANSACTION", "ANBTCalibrateYieldCurve", req.getMessageCorrelationId(), "SUCCESS");
        TelemetryRecorder.recordMetric("ANBTCalibrateYieldCurve.execution.count", 1.0);

        // Step 5: Construct and return Output Message Object
        MO_OUT_YieldCurveQuery resp = new MO_OUT_YieldCurveQuery();
        resp.setMessageCorrelationId(req.getMessageCorrelationId());
        return resp;
    }

    public boolean checkTransactionEligibility(String correlationId) {
        return this.dataGrabber.exists(correlationId);
    }
}
