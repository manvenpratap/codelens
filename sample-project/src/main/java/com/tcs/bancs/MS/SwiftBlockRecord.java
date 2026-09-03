package com.tcs.bancs.MS;

import java.io.Serializable;

/**
 * TCS BaNCS Java 17 Immutable Record: SwiftBlockRecord
 */
public record SwiftBlockRecord(int blockNumber, String content) implements Serializable {
}
