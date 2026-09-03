package com.example.trading.notification;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

public class SubscriberRegistry {
    private final Map<String, Set<String>> topicSubscribers = new ConcurrentHashMap<>();

    public void subscribe(String topic, String subscriberId) {
        topicSubscribers.computeIfAbsent(topic, k -> new CopyOnWriteArraySet<>()).add(subscriberId);
    }

    public Set<String> getSubscribers(String topic) {
        return topicSubscribers.getOrDefault(topic, Set.of());
    }
}
