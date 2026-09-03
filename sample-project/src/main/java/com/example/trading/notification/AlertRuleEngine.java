package com.example.trading.notification;

public class AlertRuleEngine {
    public boolean shouldTriggerAlert(double lossAmount, double threshold) {
        return lossAmount > threshold;
    }
}
