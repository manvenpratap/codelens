package com.tcs.bancs.CU;

import java.util.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Inbound Channel Controller: CustomerManagementController
 * Dispatches inbound requests to primary service transaction methods.
 */
public class CustomerManagementController {

    private final CustomerOnboardingService service;

    public CustomerManagementController() {
        this.service = new CustomerOnboardingService();
    }

    public CustomerManagementController(CustomerOnboardingService service) {
        this.service = service;
    }

    /**
     * Inbound Business Transaction dispatch method: CUBTOnboardCustomer
     */
    public MO_OUT_CustomerOnboarding CUBTOnboardCustomer(MO_INP_CustomerOnboarding request) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "CustomerManagementController", request != null ? request.getMessageCorrelationId() : "", "MUTATION");
        return this.service.CUBTOnboardCustomer(request);
    }

    /**
     * Inbound Elementary Transaction dispatch method: CUETGetCustomerProfile
     */
    public MO_OUT_CustomerOnboarding CUETGetCustomerProfile(String queryKey) {
        AuditTrailService.logAuditEvent("CONTROLLER_INBOUND", "CustomerManagementController", queryKey, "INQUIRY");
        return this.service.CUETGetCustomerProfile(queryKey);
    }

    /**
     * Generic execute request handler delegating to CUBTOnboardCustomer.
     */
    public MO_OUT_CustomerOnboarding handleExecuteRequest(MO_INP_CustomerOnboarding request) {
        return this.CUBTOnboardCustomer(request);
    }

    /**
     * Generic inquiry request handler delegating to CUETGetCustomerProfile.
     */
    public MO_OUT_CustomerOnboarding handleInquiryRequest(String queryKey) {
        return this.CUETGetCustomerProfile(queryKey);
    }

    public boolean ping() {
        return true;
    }
}
