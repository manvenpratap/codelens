package com.example.trading.config;

public class ClusterNodeCoordinator {
    private final String nodeId;
    private boolean leader = false;

    public ClusterNodeCoordinator(String nodeId) {
        this.nodeId = nodeId;
    }

    public String getNodeId() { return nodeId; }
    public boolean isLeader() { return leader; }
    public void electLeader() { this.leader = true; }
}
