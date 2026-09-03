package com.tcs.bancs.CL;

import java.io.Serializable;

/**
 * TCS BaNCS Java 17 Immutable Record: CustodyMovementRecord
 */
public record CustodyMovementRecord(String isin, int units, String fromParty, String toParty) implements Serializable {
}
