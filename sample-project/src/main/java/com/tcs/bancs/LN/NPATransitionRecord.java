package com.tcs.bancs.LN;

import java.io.Serializable;

/**
 * TCS BaNCS Java 17 Immutable Record: NPATransitionRecord
 */
public record NPATransitionRecord(String loanId, String previousNPA, String newNPA, int dpd) implements Serializable {
}
