package com.tcs.bancs.RK;

import java.io.Serializable;

/**
 * TCS BaNCS Java 17 Immutable Record: LimitBreachRecord
 */
public record LimitBreachRecord(String limitId, double breachAmount, long time) implements Serializable {
}
