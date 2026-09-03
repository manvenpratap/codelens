package com.example.trading.accounting;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SubLedgerRepository {
    private final Map<String, String> subLedgerCodes = new ConcurrentHashMap<>();

    public void registerAccount(String accountId, String subLedgerCode) {
        subLedgerCodes.put(accountId, subLedgerCode);
    }

    public String getSubLedger(String accountId) {
        return subLedgerCodes.getOrDefault(accountId, "DEFAULT_LEDGER");
    }
}
