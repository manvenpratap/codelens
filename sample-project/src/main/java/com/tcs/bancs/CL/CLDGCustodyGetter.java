package com.tcs.bancs.CL;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import com.tcs.bancs.common.*;

/**
 * TCS BaNCS Data Getter: CLDGCustodyGetter
 * Archetype: Cursor Data Getter
 * Specialized query and data retrieval component for CL domain entities.
 */
public class CLDGCustodyGetter {

    private final Map<String, DepositoryAccount> entityCache = new ConcurrentHashMap<>();

    public CLDGCustodyGetter() {
    }

    /**
     * Type 3: Cursor Data Getter - Streaming cursor lifecycle (Open, Fetch, Close).
     */
    private Iterator<DepositoryAccount> cursorIterator;
    private boolean cursorOpen = false;

    public void Open(String criteria) {
        this.cursorIterator = retrieveAll().iterator();
        this.cursorOpen = true;
        AuditTrailService.logAuditEvent("DATA_GETTER_CURSOR_OPEN", "CLDGCustodyGetter", criteria, "OPEN");
    }

    public DepositoryAccount Fetch() {
        if (!cursorOpen || cursorIterator == null || !cursorIterator.hasNext()) {
            return null;
        }
        return cursorIterator.next();
    }

    public void Close() {
        this.cursorOpen = false;
        this.cursorIterator = null;
        AuditTrailService.logAuditEvent("DATA_GETTER_CURSOR_CLOSE", "CLDGCustodyGetter", "STATUS", "CLOSED");
    }

    public DepositoryAccount fetchDepositoryAccountById(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return entityCache.computeIfAbsent(id, k -> {
            DepositoryAccount entity = new DepositoryAccount();
            entity.Get(k);
            return entity;
        });
    }

    public List<DepositoryAccount> retrieveAll() {
        List<DepositoryAccount> results = new ArrayList<>(entityCache.values());
        if (results.isEmpty()) {
            DepositoryAccount sample = new DepositoryAccount();
            sample.Get("DEFAULT_" + System.currentTimeMillis());
            results.add(sample);
        }
        return results;
    }

    public List<DepositoryAccount> retrieveActiveDepositoryAccounts() {
        return retrieveAll();
    }

    public MO_FailReport grabDepositoryAccountSummary(String id) {
        DepositoryAccount entity = fetchDepositoryAccountById(id);
        MO_FailReport summary = new MO_FailReport();
        summary.setMessageCorrelationId("DG_SUMMARY_" + id);
        AuditTrailService.logAuditEvent("DATA_GETTER_QUERY", "CLDGCustodyGetter", id, "grabSummary");
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
