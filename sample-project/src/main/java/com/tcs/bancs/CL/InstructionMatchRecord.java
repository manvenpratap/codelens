package com.tcs.bancs.CL;

import java.io.Serializable;

/**
 * TCS BaNCS Java 17 Immutable Record: InstructionMatchRecord
 */
public record InstructionMatchRecord(String instructionId, boolean matched, long time) implements Serializable {
}
