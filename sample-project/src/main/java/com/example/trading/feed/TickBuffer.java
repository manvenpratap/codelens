package com.example.trading.feed;

import java.util.List;
import java.util.ArrayList;

public class TickBuffer {
    private final List<Level2Quote> buffer = new ArrayList<>();

    public synchronized void append(Level2Quote quote) { buffer.add(quote); }
    public synchronized List<Level2Quote> flush() {
        List<Level2Quote> snapshot = new ArrayList<>(buffer);
        buffer.clear();
        return snapshot;
    }
}
