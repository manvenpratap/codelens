package com.tcs.bancs.CU;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_INP_CustomerOnboarding
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_INP_CustomerOnboarding implements Serializable {

    private static final long serialVersionUID = 1L;

    private String taxId;
    private String name;
    private String customerType;
    private String country;
    private String segment;
    private String messageCorrelationId;

    public MO_INP_CustomerOnboarding() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_INP_CustomerOnboarding(String taxId, String name, String customerType, String country, String segment) {
        this();
        this.taxId = taxId;
        this.name = name;
        this.customerType = customerType;
        this.country = country;
        this.segment = segment;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getTaxId() {
        return this.taxId;
    }
    public void setTaxId(String taxId) {
        this.taxId = taxId;
    }
    public String getName() {
        return this.name;
    }
    public void setName(String name) {
        this.name = name;
    }
    public String getCustomerType() {
        return this.customerType;
    }
    public void setCustomerType(String customerType) {
        this.customerType = customerType;
    }
    public String getCountry() {
        return this.country;
    }
    public void setCountry(String country) {
        this.country = country;
    }
    public String getSegment() {
        return this.segment;
    }
    public void setSegment(String segment) {
        this.segment = segment;
    }

    @Override
    public String toString() {
        return "MO_INP_CustomerOnboarding{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
