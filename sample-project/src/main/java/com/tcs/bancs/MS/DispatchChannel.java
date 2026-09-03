package com.tcs.bancs.MS;

/**
 * TCS BaNCS Domain Enumeration: DispatchChannel
 */
public enum DispatchChannel {
    MQ_SERIES,
    KAFKA_STREAM,
    REST_WEBHOOK,
    SWIFT_ALLIANCE;

    public boolean isValid() {
        return true;
    }
}
