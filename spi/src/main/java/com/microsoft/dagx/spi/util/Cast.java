/*
 * Copyright (c) Microsoft Corporation.
 * All rights reserved.
 */

package com.microsoft.dagx.spi.util;

/**
 * Utility for performing typesafe casts.
 */
public class Cast {

    @SuppressWarnings("unchecked")
    public static <T> T cast(Object type) {
        return (T) type;
    }
}
