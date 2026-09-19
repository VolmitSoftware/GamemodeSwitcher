package com.volmit.gsw.gameplay;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class TapTracker {
    private final Map<UUID, Sequence> sequences = new ConcurrentHashMap<>();

    public boolean tap(UUID player, String action, long now, long windowNanos, int required) {
        Sequence previous = sequences.get(player);
        int count = previous != null && previous.action().equals(action)
                && now - previous.lastTap() >= 0 && now - previous.lastTap() <= windowNanos
                ? previous.count() + 1 : 1;
        if (count >= required) {
            sequences.remove(player);
            return true;
        }
        sequences.put(player, new Sequence(action, now, count));
        return false;
    }

    public void remove(UUID player) {
        sequences.remove(player);
    }

    public void clear() {
        sequences.clear();
    }

    public int size() {
        return sequences.size();
    }

    private record Sequence(String action, long lastTap, int count) {
    }
}
