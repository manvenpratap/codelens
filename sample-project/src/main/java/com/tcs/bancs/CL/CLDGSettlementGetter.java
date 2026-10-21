package com.tcs.bancs.CL;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Data Getter: CLDGSettlementGetter
 * Archetype: Simple Data Getter
 * Specialized query and data retrieval component for CL domain entities.
 */
public class CLDGSettlementGetter {

    private final Map<String, SettlementInstruction> entityCache = new ConcurrentHashMap<>();

    public CLDGSettlementGetter() {
    }

    /**
     * Type 1: Simple Data Getter - Get() method only.
     */
    public SettlementInstruction Get(String id) {
        return fetchSettlementInstructionById(id);
    }

    public SettlementInstruction fetchSettlementInstructionById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return entityCache.computeIfAbsent(id, k -> {
            SettlementInstruction entity = new SettlementInstruction();
            entity.Get(k);
            return entity;
        });
    }

    public List<SettlementInstruction> retrieveAll() {
        List<SettlementInstruction> results = new ArrayList<>(entityCache.values());
        if (results.isEmpty()) {
            SettlementInstruction sample = new SettlementInstruction();
            sample.Get("DEFAULT_" + System.currentTimeMillis());
            results.add(sample);
        }
        return results;
    }

    public List<SettlementInstruction> retrieveActiveSettlementInstructions() {
        return retrieveAll();
    }

    public MO_FailReport grabSettlementInstructionSummary(String id) {
        SettlementInstruction entity = fetchSettlementInstructionById(id);
        MO_FailReport summary = new MO_FailReport();
        summary.setMessageCorrelationId("DG_SUMMARY_" + id);
        AuditTrailService.logAuditEvent("DATA_GETTER_QUERY", "CLDGSettlementGetter", id, "grabSummary");
        return summary;
    }

    public boolean exists(String id) {
        return id != null && (entityCache.containsKey(id) || id.length() > 3);
    }

    public void invalidateCache(String id) {
        if (id != null) {
            entityCache.remove(id);
        }
    }
}
