package com.example.trading.accounting;

import java.util.List;
import java.util.ArrayList;

public class GeneralLedgerService {
    private final List<JournalEntry> ledgerEntries = new ArrayList<>();

    public synchronized void postEntry(JournalEntry entry) {
        ledgerEntries.add(entry);
    }

    public synchronized List<JournalEntry> getEntries() {
        return new ArrayList<>(ledgerEntries);
    }
}
