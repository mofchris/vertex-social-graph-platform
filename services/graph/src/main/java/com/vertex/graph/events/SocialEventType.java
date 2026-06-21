package com.vertex.graph.events;

/**
 * The social events Graph emits when a relationship changes. The {@code name()} of each value is
 * the value that travels on the wire; downstream consumers map it onto their own vocabulary
 * (e.g. Notify's {@code NotificationType}). Keep these names stable — they are a contract.
 */
public enum SocialEventType {
    FOLLOW,
    FRIEND_REQUEST,
    FRIEND_ACCEPT
}
