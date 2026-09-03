package com.tcs.bancs.MS;

import java.util.*;

/**
 * TCS BaNCS Enterprise Service Contract: MessageDispatcher
 */
public interface MessageDispatcher {
    boolean dispatch(String queue, String msg);
    String receive(String queue);
}
