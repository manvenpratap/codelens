package com.tcs.bancs.GL;

import java.io.Serializable;
import java.util.*;
import java.time.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Message Object: MO_CostCenterRollup
 * Standard DTO structure for request/response payloads and host integration.
 */
public class MO_CostCenterRollup implements Serializable {

    private static final long serialVersionUID = 1L;

    private String costCenter;
    private double totalExpense;
    private double totalRevenue;
    private String messageCorrelationId;

    public MO_CostCenterRollup() {
        this.messageCorrelationId = UUID.randomUUID().toString();
    }

    public MO_CostCenterRollup(String costCenter, double totalExpense, double totalRevenue) {
        this();
        this.costCenter = costCenter;
        this.totalExpense = totalExpense;
        this.totalRevenue = totalRevenue;
    }

    public String getMessageCorrelationId() {
        return this.messageCorrelationId;
    }

    public void setMessageCorrelationId(String messageCorrelationId) {
        this.messageCorrelationId = messageCorrelationId;
    }

    public String getCostCenter() {
        return this.costCenter;
    }
    public void setCostCenter(String costCenter) {
        this.costCenter = costCenter;
    }
    public double getTotalExpense() {
        return this.totalExpense;
    }
    public void setTotalExpense(double totalExpense) {
        this.totalExpense = totalExpense;
    }
    public double getTotalRevenue() {
        return this.totalRevenue;
    }
    public void setTotalRevenue(double totalRevenue) {
        this.totalRevenue = totalRevenue;
    }

    @Override
    public String toString() {
        return "MO_CostCenterRollup{" +
               "correlationId='" + messageCorrelationId + "'" +
               "}";
    }
}
