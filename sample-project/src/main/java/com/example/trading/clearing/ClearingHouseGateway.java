package com.example.trading.clearing;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class ClearingHouseGateway {
    private final List<SettlementRecord> submittedRecords = new CopyOnWriteArrayList<>();

    public void submitForClearing(SettlementRecord record) {
        submittedRecords.add(record);
    }

    public int getPendingRecordCount() {
        return submittedRecords.size();
    }
}
