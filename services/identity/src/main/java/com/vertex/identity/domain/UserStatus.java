package com.vertex.identity.domain;

/** Lifecycle of an account. DELETED is a soft-delete tombstone (the row and immutable id stay). */
public enum UserStatus {
    ACTIVE,
    DELETED
}
