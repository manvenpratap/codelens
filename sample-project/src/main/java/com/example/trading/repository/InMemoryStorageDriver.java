package com.example.trading.repository;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryStorageDriver<K, V> {
    private final Map<K, V> store = new ConcurrentHashMap<>();

    public void put(K key, V val) { store.put(key, val); }
    public V get(K key) { return store.get(key); }
    public boolean contains(K key) { return store.containsKey(key); }
    public int size() { return store.size(); }
}
