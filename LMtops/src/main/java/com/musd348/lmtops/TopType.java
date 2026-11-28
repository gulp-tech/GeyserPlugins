package com.musd348.lmtops;

public enum TopType {
    BALANCE,
    KILLS,
    DEATHS,
    SHARDS,
    BLOCKS,
    PLAYTIME;

    public static TopType fromString(String type) {
        if (type == null) return null;
        try {
            return TopType.valueOf(type.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}