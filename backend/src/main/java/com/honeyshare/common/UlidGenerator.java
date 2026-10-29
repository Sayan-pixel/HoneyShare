package com.honeyshare.common;

import com.github.f4b6a3.ulid.UlidCreator;

/**
 * Generates URL-safe, monotonically sortable ULID strings used as
 * opaque primary keys throughout the system.
 *
 * <p>ULIDs are 26-character Crockford Base32 strings, globally unique
 * without coordination, and sortable by creation time.
 */
public final class UlidGenerator {

    private UlidGenerator() {}

    /** Generate a new monotonic ULID string. */
    public static String generate() {
        return UlidCreator.getMonotonicUlid().toString();
    }
}

