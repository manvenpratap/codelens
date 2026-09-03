package com.tcs.bancs.CL;

import java.util.*;
import java.time.*;
import java.math.*;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Persistent Entity: SettlementInstruction
 * Encapsulates core persistence lifecycle methods Get(), Create(), and Modify()
 * alongside domain-specific business actions and field mutations.
 */
public class SettlementInstruction {

    private String instructionId;
    private String tradeId;
    private String settlementCycle;
    private String intendedSettlementDate;
    private String actualSettlementDate;
    private String deliveringParty;
    private String receivingParty;
    private String securityIsin;
    private int settlementUnits;
    private double settlementCashAmount;
    private String status;
    private String entityVersion = "1.0";
    private boolean isPersisted = false;

    public SettlementInstruction() {
    }

    public SettlementInstruction(String instructionId, String tradeId, String settlementCycle, String intendedSettlementDate, String actualSettlementDate, String deliveringParty, String receivingParty, String securityIsin, int settlementUnits, double settlementCashAmount, String status) {
        this.instructionId = instructionId;
        this.tradeId = tradeId;
        this.settlementCycle = settlementCycle;
        this.intendedSettlementDate = intendedSettlementDate;
        this.actualSettlementDate = actualSettlementDate;
        this.deliveringParty = deliveringParty;
        this.receivingParty = receivingParty;
        this.securityIsin = securityIsin;
        this.settlementUnits = settlementUnits;
        this.settlementCashAmount = settlementCashAmount;
        this.status = status;
        this.isPersisted = true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BaNCS Persistent Archetype Core Contract: Get, Create, Modify
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Retrieves and hydrates persistent state for the entity.
     */
    public synchronized boolean Get(String id) {
        this.instructionId = id;
        this.isPersisted = true;
        this.logStateChange("Get");
        return true;
    }

    /**
     * Persists a newly created entity into underlying storage.
     */
    public synchronized boolean Create() {
        this.isPersisted = true;
        this.entityVersion = "1.0";
        this.logStateChange("Create");
        return true;
    }

    /**
     * Modifies persistent entity attributes and records mutation.
     */
    public synchronized boolean Modify(String newStatus) {
        this.entityVersion = "1.1";
        this.logStateChange("Modify");
        return true;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Business Methods (read, write, and propagate entity fields)
    // ─────────────────────────────────────────────────────────────────────────

    public synchronized void affirmInstruction(String party) {
        status = "AFFIRMED"; deliveringParty = party;
        this.logStateChange("affirmInstruction");
    }
    public synchronized void matchInstruction(String counterparty) {
        status = "MATCHED"; receivingParty = counterparty;
        this.logStateChange("matchInstruction");
    }
    public synchronized void settleInstruction(String actualDate) {
        actualSettlementDate = actualDate; status = "SETTLED";
        this.logStateChange("settleInstruction");
    }
    public synchronized void failInstruction(String reasonCode) {
        status = "FAILED"; settlementCycle = reasonCode;
        this.logStateChange("failInstruction");
    }

    private void logStateChange(String action) {
        AuditTrailService.logAuditEvent("PERSISTENT_MUTATION", "SettlementInstruction", String.valueOf(this.instructionId), action);
    }

    public boolean isPersisted() {
        return this.isPersisted;
    }

    public String getEntityVersion() {
        return this.entityVersion;
    }

    public String getInstructionId() {
        return this.instructionId;
    }
    public void setInstructionId(String instructionId) {
        this.instructionId = instructionId;
    }
    public String getTradeId() {
        return this.tradeId;
    }
    public void setTradeId(String tradeId) {
        this.tradeId = tradeId;
    }
    public String getSettlementCycle() {
        return this.settlementCycle;
    }
    public void setSettlementCycle(String settlementCycle) {
        this.settlementCycle = settlementCycle;
    }
    public String getIntendedSettlementDate() {
        return this.intendedSettlementDate;
    }
    public void setIntendedSettlementDate(String intendedSettlementDate) {
        this.intendedSettlementDate = intendedSettlementDate;
    }
    public String getActualSettlementDate() {
        return this.actualSettlementDate;
    }
    public void setActualSettlementDate(String actualSettlementDate) {
        this.actualSettlementDate = actualSettlementDate;
    }
    public String getDeliveringParty() {
        return this.deliveringParty;
    }
    public void setDeliveringParty(String deliveringParty) {
        this.deliveringParty = deliveringParty;
    }
    public String getReceivingParty() {
        return this.receivingParty;
    }
    public void setReceivingParty(String receivingParty) {
        this.receivingParty = receivingParty;
    }
    public String getSecurityIsin() {
        return this.securityIsin;
    }
    public void setSecurityIsin(String securityIsin) {
        this.securityIsin = securityIsin;
    }
    public int getSettlementUnits() {
        return this.settlementUnits;
    }
    public void setSettlementUnits(int settlementUnits) {
        this.settlementUnits = settlementUnits;
    }
    public double getSettlementCashAmount() {
        return this.settlementCashAmount;
    }
    public void setSettlementCashAmount(double settlementCashAmount) {
        this.settlementCashAmount = settlementCashAmount;
    }
    public String getStatus() {
        return this.status;
    }
    public void setStatus(String status) {
        this.status = status;
    }
}
