package com.example.trading.feed;

import java.util.HashMap;
import java.util.Map;

public class FixMessageParser {
    public Map<Integer, String> parseFixTags(String rawFix) {
        Map<Integer, String> tags = new HashMap<>();
        String[] parts = rawFix.split("\\|");
        for (String p : parts) {
            String[] kv = p.split("=");
            if (kv.length == 2) {
                try {
                    tags.put(Integer.parseInt(kv[0]), kv[1]);
                } catch (NumberFormatException ignored) {}
            }
        }
        return tags;
    }
}
