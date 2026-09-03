package com.example.trading.reporting;

import java.util.List;

public class CsvExportFormatter {
    public String toCsv(List<String> headers, List<List<String>> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.join(",", headers)).append("\n");
        for (List<String> row : rows) {
            sb.append(String.join(",", row)).append("\n");
        }
        return sb.toString();
    }
}
