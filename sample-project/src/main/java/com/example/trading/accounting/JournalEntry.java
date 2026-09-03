package com.example.trading.accounting;

public record JournalEntry(
    String entryId,
    String accountId,
    String entryType,
    double debit,
    double credit,
    long timestamp,
    String description
) {}
