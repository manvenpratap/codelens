package com.example.trading.repository;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class AccountRepository {
    private final Map<String, String> accountOwners = new ConcurrentHashMap<>();

    public void register(String accountId, String owner) { accountOwners.put(accountId, owner); }
    public String getOwner(String accountId) { return accountOwners.get(accountId); }
}
