package com.example.trading.config;

public class DatabaseProperties {
    private String jdbcUrl = "jdbc:h2:mem:trading";
    private int poolSize = 10;

    public String getJdbcUrl() { return jdbcUrl; }
    public int getPoolSize() { return poolSize; }
}
