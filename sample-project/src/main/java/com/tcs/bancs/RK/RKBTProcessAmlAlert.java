package com.tcs.bancs.RK;

import java.util.*;
import com.tcs.bancs.common.*;
import com.tcs.bancs.AM.*;

/**
 * TCS BaNCS Business Transaction: RKBTProcessAmlAlert
 * Orchestrates multi-step transactional workflows, entity mutation, and ledger updates.
 */
public class RKBTProcessAmlAlert {

    private final RKDGLimitGrabber dataGrabber;
    private final CreditRiskService service;

    public RKBTProcessAmlAlert() {
        this.dataGrabber = new RKDGLimitGrabber();
        this.service = new CreditRiskService();
    }

    public RKBTProcessAmlAlert(RKDGLimitGrabber dataGrabber, CreditRiskService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary BaNCS Business Transaction entry point: RKBTProcessAmlAlertExecute
     */
    public MO_OUT_AmlScreening RKBTProcessAmlAlertExecute(MO_INP_AmlScreening req) {
        if (req == null) {
            throw new ValidationException("Input message object cannot be null in " + "RKBTProcessAmlAlert");
        }

        // Step 1: Pre-transaction validation via service
        boolean isValid = this.service.validateTransactionPreconditions(req.getMessageCorrelationId());
        if (!isValid) {
            throw new BusinessException("Validation failed in " + "RKBTProcessAmlAlert");
        }

        // Step 2: Data Grabber state query
        PartyRiskLimit entity = this.dataGrabber.fetchPartyRiskLimitById(req.getMessageCorrelationId());

        // Step 3: Domain Entity Mutation
        if (entity != null) {
            entity.Create();
            entity.Modify("EXECUTED");
        }

        // Cross-module integration: RK -> AM
        AuditTrailService.logAuditEvent("CROSS_MODULE_CALL", "RKBTProcessAmlAlert", req.getMessageCorrelationId(), "AM");

        // Step 4: Audit & Telemetry
        AuditTrailService.logAuditEvent("BUSINESS_TRANSACTION", "RKBTProcessAmlAlert", req.getMessageCorrelationId(), "SUCCESS");
        TelemetryRecorder.recordMetric("RKBTProcessAmlAlert.execution.count", 1.0);

        // Step 5: Construct and return Output Message Object
        MO_OUT_AmlScreening resp = new MO_OUT_AmlScreening();
        resp.setMessageCorrelationId(req.getMessageCorrelationId());
        return resp;
    }

    public boolean checkTransactionEligibility(String correlationId) {
        return this.dataGrabber.exists(correlationId);
    }
}
