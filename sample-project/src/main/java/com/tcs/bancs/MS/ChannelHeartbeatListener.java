package com.tcs.bancs.MS;

import java.util.*;

/**
 * TCS BaNCS Enterprise Service Contract: ChannelHeartbeatListener
 */
public interface ChannelHeartbeatListener {
    void onHeartbeat(String channel, boolean isAlive);
}
