package com.example.trading.reporting;

public class ReportScheduleCoordinator {
    public boolean isTimeToRunDailyReport(int hourOfDay) {
        return hourOfDay == 17; // EOD 5 PM
    }
}
