package com.tcs.bancs.CL;

import java.io.Serializable;

/**
 * TCS BaNCS Java 17 Immutable Record: NetObligationRecord
 */
public record NetObligationRecord(String memberId, double cash, int units) implements Serializable {
}
