package com.example.trading.repository;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

public class InstrumentRepository {
    private final Set<String> tradableSymbols = new CopyOnWriteArraySet<>();

    public void addSymbol(String symbol) { tradableSymbols.add(symbol); }
    public boolean isTradable(String symbol) { return tradableSymbols.contains(symbol); }
}
