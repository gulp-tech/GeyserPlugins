package com.musd348.lmtops;

import java.util.UUID;

public record TopEntry(UUID uuid, String name, double value) {
    public TopEntry(String name, double value) {
        this(null, name, value);
    }
}