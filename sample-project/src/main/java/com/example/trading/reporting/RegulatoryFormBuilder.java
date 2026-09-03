package com.example.trading.reporting;

public class RegulatoryFormBuilder {
    public String buildCatReportHeader(String firmCrd, String eventDate) {
        return String.format("CAT-FIRM:%s|DATE:%s|VERSION:2.2", firmCrd, eventDate);
    }
}
