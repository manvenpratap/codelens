package com.tcs.bancs.TR;

import java.util.*;
import com.tcs.bancs.common.*;
import com.tcs.bancs.CL.*;

/**
 * TCS BaNCS Business Transaction: TRBTRebalancePortfolio
 * Orchestrates multi-step transactional workflows, entity mutation, and ledger updates.
 */
public class TRBTRebalancePortfolio {

    private final TRDGTradeGrabber dataGrabber;
    private final AlgorithmicPricingService service;

    public TRBTRebalancePortfolio() {
        this.dataGrabber = new TRDGTradeGrabber();
        this.service = new AlgorithmicPricingService();
    }

    public TRBTRebalancePortfolio(TRDGTradeGrabber dataGrabber, AlgorithmicPricingService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary BaNCS Business Transaction entry point: TRBTRebalancePortfolioExecute
     */
    public MO_PortfolioPosition TRBTRebalancePortfolioExecute(MO_TradeExecutionReport req) {
        if (req == null) {
            throw new ValidationException("Input message object cannot be null in " + "TRBTRebalancePortfolio");
        }

        // Step 1: Pre-transaction validation via service
        boolean isValid = this.service.validateTransactionPreconditions(req.getMessageCorrelationId());
        if (!isValid) {
            throw new BusinessException("Validation failed in " + "TRBTRebalancePortfolio");
        }

        // Step 2: Data Grabber state query
        OrderEntity entity = this.dataGrabber.fetchOrderEntityById(req.getMessageCorrelationId());

        // Step 3: Domain Entity Mutation
        if (entity != null) {
            entity.Create();
            entity.Modify("EXECUTED");
        }

        // Cross-module integration: TR -> CL
        AuditTrailService.logAuditEvent("CROSS_MODULE_CALL", "TRBTRebalancePortfolio", req.getMessageCorrelationId(), "CL");

        // Step 4: Audit & Telemetry
        AuditTrailService.logAuditEvent("BUSINESS_TRANSACTION", "TRBTRebalancePortfolio", req.getMessageCorrelationId(), "SUCCESS");
        TelemetryRecorder.recordMetric("TRBTRebalancePortfolio.execution.count", 1.0);

        // Step 5: Construct and return Output Message Object
        MO_PortfolioPosition resp = new MO_PortfolioPosition();
        resp.setMessageCorrelationId(req.getMessageCorrelationId());
        return resp;
    }

    public boolean checkTransactionEligibility(String correlationId) {
        return this.dataGrabber.exists(correlationId);
    }
}
