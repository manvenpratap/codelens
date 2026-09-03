package com.tcs.bancs.PM;

import java.io.Serializable;

/**
 * TCS BaNCS Java 17 Immutable Record: RoutingHopRecord
 */
public record RoutingHopRecord(String network, String bic, double cost) implements Serializable {
}
