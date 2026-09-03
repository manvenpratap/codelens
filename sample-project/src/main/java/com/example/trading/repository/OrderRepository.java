package com.example.trading.repository;

import com.example.trading.model.Order;
import java.util.List;
import java.util.ArrayList;

public class OrderRepository {
    private final InMemoryStorageDriver<String, Order> storage = new InMemoryStorageDriver<>();

    public void save(Order order) { storage.put(order.getOrderId(), order); }
    public Order findById(String orderId) { return storage.get(orderId); }
    public int count() { return storage.size(); }
}
