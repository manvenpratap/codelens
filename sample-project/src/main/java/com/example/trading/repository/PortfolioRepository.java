package com.example.trading.repository;

import com.example.trading.model.Portfolio;

public class PortfolioRepository {
    private final InMemoryStorageDriver<String, Portfolio> storage = new InMemoryStorageDriver<>();

    public void save(Portfolio portfolio) { storage.put(portfolio.getAccountId(), portfolio); }
    public Portfolio findByAccountId(String accountId) { return storage.get(accountId); }
}
