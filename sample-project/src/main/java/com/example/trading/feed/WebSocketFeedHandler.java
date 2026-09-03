package com.example.trading.feed;

public class WebSocketFeedHandler {
    private boolean connected = false;

    public void onConnect() { connected = true; }
    public void onDisconnect() { connected = false; }
    public boolean isConnected() { return connected; }
}
