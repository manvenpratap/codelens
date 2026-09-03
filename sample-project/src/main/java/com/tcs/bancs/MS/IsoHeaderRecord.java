package com.tcs.bancs.MS;

import java.io.Serializable;

/**
 * TCS BaNCS Java 17 Immutable Record: IsoHeaderRecord
 */
public record IsoHeaderRecord(String bizMsgId, String creationDate) implements Serializable {
}
