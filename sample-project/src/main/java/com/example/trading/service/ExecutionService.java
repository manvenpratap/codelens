package com.example.trading.service;

import com.example.trading.api.ExecutionReport;
import com.example.trading.api.OrderRequest;
import com.example.trading.engine.MatchingEngine;
import com.example.trading.model.Portfolio;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Dispatches orders to the matching engine and records executions into portfolios.
 */
public class ExecutionService {

    private final MatchingEngine matchingEngine;
    private final List<ExecutionReport> executionHistory = new ArrayList<>();

    public ExecutionService(MatchingEngine matchingEngine) {
        this.matchingEngine = matchingEngine;
    }

    public synchronized ExecutionReport execute(OrderRequest request, Portfolio portfolio) {
        ExecutionReport report = matchingEngine.matchOrder(request);
        portfolio.recordFill(report.symbol(), report.filledQuantity(), report.averageFillPrice(), report.fee());
        executionHistory.add(report);
        return report;
    }

    public synchronized List<ExecutionReport> getHistory() {
        return Collections.unmodifiableList(executionHistory);
    }
}
