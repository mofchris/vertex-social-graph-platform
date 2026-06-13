package com.vertex.graph.domain;

/** Lifecycle of a friendship row. Rejected/cancelled requests delete the row instead. */
public enum FriendshipStatus {
    PENDING,
    ACCEPTED
}
