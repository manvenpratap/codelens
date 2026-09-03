package com.tcs.bancs.MS;

import java.io.Serializable;

/**
 * TCS BaNCS Java 17 Immutable Record: FixFieldRecord
 */
public record FixFieldRecord(int tag, String value) implements Serializable {
}
