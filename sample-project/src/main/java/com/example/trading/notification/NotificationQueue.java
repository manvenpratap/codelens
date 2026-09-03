package com.example.trading.notification;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class NotificationQueue {
    private final Queue<String> queue = new ConcurrentLinkedQueue<>();

    public void enqueue(String message) { queue.offer(message); }
    public String dequeue() { return queue.poll(); }
    public int size() { return queue.size(); }
}
