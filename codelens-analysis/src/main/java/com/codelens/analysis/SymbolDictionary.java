package com.codelens.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * High-performance, memory-efficient symbol dictionary mapping strings (FQNs)
 * to dense 0-based 32-bit integers and vice-versa.
 */
public class SymbolDictionary {

    private final ConcurrentHashMap<String, Integer> symbolToId = new ConcurrentHashMap<>();
    private final List<String> idToSymbol = new ArrayList<>();
    private final Object writeLock = new Object();

    public SymbolDictionary() {}

    public SymbolDictionary(int initialCapacity) {
        // ArrayList and ConcurrentHashMap sized accordingly
    }

    public int getOrRegister(String symbol) {
        if (symbol == null) return -1;
        Integer existing = symbolToId.get(symbol);
        if (existing != null) return existing;

        synchronized (writeLock) {
            existing = symbolToId.get(symbol);
            if (existing != null) return existing;

            int newId = idToSymbol.size();
            idToSymbol.add(symbol);
            symbolToId.put(symbol, newId);
            return newId;
        }
    }

    public int getId(String symbol) {
        if (symbol == null) return -1;
        Integer id = symbolToId.get(symbol);
        return id != null ? id : -1;
    }

    public String getSymbol(int id) {
        if (id < 0 || id >= idToSymbol.size()) return null;
        return idToSymbol.get(id);
    }

    public int size() {
        return idToSymbol.size();
    }

    public boolean contains(String symbol) {
        return symbol != null && symbolToId.containsKey(symbol);
    }

    public void clear() {
        synchronized (writeLock) {
            symbolToId.clear();
            idToSymbol.clear();
        }
    }
}
