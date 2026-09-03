package com.tcs.bancs.MS;

import java.io.Serializable;

/**
 * TCS BaNCS Java 17 Immutable Record: RoutingHopRecord
 */
public record RoutingHopRecord(String hopId, String nodeName, long latencyMs) implements Serializable {
}
