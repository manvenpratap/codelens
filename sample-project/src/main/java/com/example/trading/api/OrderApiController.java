package com.example.trading.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.context.event.EventListener;

/**
 * REST Endpoint controller exposing Order operations and listening for Domain Events.
 */
@RestController
@RequestMapping("/api/v1/orders")
public class OrderApiController {

    @GetMapping("/{orderId}")
    public OrderRequest getOrder(@PathVariable("orderId") String orderId) {
        return null;
    }

    @PostMapping
    public OrderRequest createOrder(@RequestBody OrderRequest request) {
        return request;
    }

    @EventListener
    public void onOrderPlaced(OrderPlacedEvent event) {
        // Domain event listener
    }
}
