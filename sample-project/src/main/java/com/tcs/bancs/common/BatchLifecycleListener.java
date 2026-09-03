package com.tcs.bancs.common;

public interface BatchLifecycleListener {
    void onBatchStarted(String batchName);
    void onBatchCompleted(String batchName, int recordsProcessed);
}
