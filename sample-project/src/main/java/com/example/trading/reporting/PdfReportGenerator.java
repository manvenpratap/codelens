package com.example.trading.reporting;

public class PdfReportGenerator {
    public byte[] generatePdfPlaceholder(String title) {
        return ("PDF-CONTENT: " + title).getBytes();
    }
}
