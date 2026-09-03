package com.tcs.bancs.CU;

import java.util.*;
import com.tcs.bancs.common.*;


/**
 * TCS BaNCS Business Transaction: CUBTDeactivateCustomer
 * Orchestrates multi-step transactional workflows, entity mutation, and ledger updates.
 */
public class CUBTDeactivateCustomer {

    private final CUDGCustomerGrabber dataGrabber;
    private final CustomerExposureRollupService service;

    public CUBTDeactivateCustomer() {
        this.dataGrabber = new CUDGCustomerGrabber();
        this.service = new CustomerExposureRollupService();
    }

    public CUBTDeactivateCustomer(CUDGCustomerGrabber dataGrabber, CustomerExposureRollupService service) {
        this.dataGrabber = dataGrabber;
        this.service = service;
    }

    /**
     * Primary BaNCS Business Transaction entry point: CUBTDeactivateCustomerExecute
     */
    public MO_OUT_BeneficialOwnerDeclaration CUBTDeactivateCustomerExecute(MO_INP_BeneficialOwnerDeclaration req) {
        if (req == null) {
            throw new ValidationException("Input message object cannot be null in " + "CUBTDeactivateCustomer");
        }

        // Step 1: Pre-transaction validation via service
        boolean isValid = this.service.validateTransactionPreconditions(req.getMessageCorrelationId());
        if (!isValid) {
            throw new BusinessException("Validation failed in " + "CUBTDeactivateCustomer");
        }

        // Step 2: Data Grabber state query
        CustomerProfile entity = this.dataGrabber.fetchCustomerProfileById(req.getMessageCorrelationId());

        // Step 3: Domain Entity Mutation
        if (entity != null) {
            entity.Create();
            entity.Modify("EXECUTED");
        }



        // Step 4: Audit & Telemetry
        AuditTrailService.logAuditEvent("BUSINESS_TRANSACTION", "CUBTDeactivateCustomer", req.getMessageCorrelationId(), "SUCCESS");
        TelemetryRecorder.recordMetric("CUBTDeactivateCustomer.execution.count", 1.0);

        // Step 5: Construct and return Output Message Object
        MO_OUT_BeneficialOwnerDeclaration resp = new MO_OUT_BeneficialOwnerDeclaration();
        resp.setMessageCorrelationId(req.getMessageCorrelationId());
        return resp;
    }

    public boolean checkTransactionEligibility(String correlationId) {
        return this.dataGrabber.exists(correlationId);
    }
}
