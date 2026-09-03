package com.tcs.bancs.CU;

import java.util.*;
import com.tcs.bancs.common.*;


/**
 * TCS BaNCS Business Transaction: CUBTVerifyKyc
 * Orchestrates multi-step transactional workflows, entity mutation, and ledger updates.
 */
public class CUBTVerifyKyc {

    private final CUDGKycGrabber dataGrabber;
    private final KycVerificationService service;

    public CUBTVerifyKyc() {
        this.dataGrabber = new CUDGKycGrabber();
        this.service = new KycVerificationService();
    }

    public CUBTVerifyKyc(CUDGKycGrabber dataGrabber, KycVerificationService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary BaNCS Business Transaction entry point: CUBTVerifyKycExecute
     */
    public MO_OUT_KycSubmission CUBTVerifyKycExecute(MO_INP_KycSubmission req) {
        if (req == null) {
            throw new ValidationException("Input message object cannot be null in " + "CUBTVerifyKyc");
        }

        // Step 1: Pre-transaction validation via service
        boolean isValid = this.service.validateTransactionPreconditions(req.getMessageCorrelationId());
        if (!isValid) {
            throw new BusinessException("Validation failed in " + "CUBTVerifyKyc");
        }

        // Step 2: Data Grabber state query
        KycDocument entity = this.dataGrabber.fetchKycDocumentById(req.getMessageCorrelationId());

        // Step 3: Domain Entity Mutation
        if (entity != null) {
            entity.Create();
            entity.Modify("EXECUTED");
        }



        // Step 4: Audit & Telemetry
        AuditTrailService.logAuditEvent("BUSINESS_TRANSACTION", "CUBTVerifyKyc", req.getMessageCorrelationId(), "SUCCESS");
        TelemetryRecorder.recordMetric("CUBTVerifyKyc.execution.count", 1.0);

        // Step 5: Construct and return Output Message Object
        MO_OUT_KycSubmission resp = new MO_OUT_KycSubmission();
        resp.setMessageCorrelationId(req.getMessageCorrelationId());
        return resp;
    }

    public boolean checkTransactionEligibility(String correlationId) {
        return this.dataGrabber.exists(correlationId);
    }
}
