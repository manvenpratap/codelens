package com.example.trading.config;

public class TelemetryConfig {
    private boolean metricsEnabled = true;
    private String exporterEndpoint = "http://localhost:4317";

    public boolean isMetricsEnabled() { return metricsEnabled; }
    public String getExporterEndpoint() { return exporterEndpoint; }
}
