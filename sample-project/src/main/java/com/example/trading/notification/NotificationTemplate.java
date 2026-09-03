package com.example.trading.notification;

public class NotificationTemplate {
    public String formatOrderAlert(String orderId, String status) {
        return String.format("Order %s updated to status: %s", orderId, status);
    }

    public String formatMarginCall(String accountId, double amount) {
        return String.format("Urgent: Account %s margin call required: $%.2f", accountId, amount);
    }
}
