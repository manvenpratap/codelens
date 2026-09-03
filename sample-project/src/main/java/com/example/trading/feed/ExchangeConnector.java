package com.example.trading.feed;

public class ExchangeConnector {
    private final String exchangeCode;

    public ExchangeConnector(String exchangeCode) {
        this.exchangeCode = exchangeCode;
    }

    public String getExchangeCode() { return exchangeCode; }
    public boolean testConnection() { return true; }
}
