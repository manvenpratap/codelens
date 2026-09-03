package com.example.trading.clearing;

public class MarginRequirementCalculator {
    private static final double INITIAL_MARGIN_RATIO = 0.20;
    private static final double MAINTENANCE_MARGIN_RATIO = 0.15;

    public double calculateInitialMargin(double positionNotional) {
        return positionNotional * INITIAL_MARGIN_RATIO;
    }

    public double calculateMaintenanceMargin(double positionNotional) {
        return positionNotional * MAINTENANCE_MARGIN_RATIO;
    }
}
