package com.tcs.bancs.CL;

import java.util.*;
import com.tcs.bancs.common.*;
import com.tcs.bancs.GL.*;

/**
 * TCS BaNCS Business Transaction: CLBTAffirmTrade
 * Orchestrates multi-step transactional workflows, entity mutation, and ledger updates.
 */
public class CLBTAffirmTrade {

    private final CLDGCustodyGrabber dataGrabber;
    private final CustodyManagementService service;

    public CLBTAffirmTrade() {
        this.dataGrabber = new CLDGCustodyGrabber();
        this.service = new CustodyManagementService();
    }

    public CLBTAffirmTrade(CLDGCustodyGrabber dataGrabber, CustodyManagementService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary BaNCS Business Transaction entry point: CLBTAffirmTradeExecute
     */
    public MO_OUT_Affirmation CLBTAffirmTradeExecute(MO_INP_Affirmation req) {
        if (req == null) {
            throw new ValidationException("Input message object cannot be null in " + "CLBTAffirmTrade");
        }

        // Step 1: Pre-transaction validation via service
        boolean isValid = this.service.validateTransactionPreconditions(req.getMessageCorrelationId());
        if (!isValid) {
            throw new BusinessException("Validation failed in " + "CLBTAffirmTrade");
        }

        // Step 2: Data Grabber state query
        DepositoryAccount entity = this.dataGrabber.fetchDepositoryAccountById(req.getMessageCorrelationId());

        // Step 3: Domain Entity Mutation
        if (entity != null) {
            entity.Create();
            entity.Modify("EXECUTED");
        }

        // Cross-module integration: CL -> GL
        AuditTrailService.logAuditEvent("CROSS_MODULE_CALL", "CLBTAffirmTrade", req.getMessageCorrelationId(), "GL");

        // Step 4: Audit & Telemetry
        AuditTrailService.logAuditEvent("BUSINESS_TRANSACTION", "CLBTAffirmTrade", req.getMessageCorrelationId(), "SUCCESS");
        TelemetryRecorder.recordMetric("CLBTAffirmTrade.execution.count", 1.0);

        // Step 5: Construct and return Output Message Object
        MO_OUT_Affirmation resp = new MO_OUT_Affirmation();
        resp.setMessageCorrelationId(req.getMessageCorrelationId());
        return resp;
    }

    public boolean checkTransactionEligibility(String correlationId) {
        return this.dataGrabber.exists(correlationId);
    }
}
