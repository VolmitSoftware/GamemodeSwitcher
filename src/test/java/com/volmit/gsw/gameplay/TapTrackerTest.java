package com.volmit.gsw.gameplay;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TapTrackerTest {
    @Test
    void expiredTapStartsANewSequenceInsteadOfConsumingTheNextTap() {
        TapTracker tracker = new TapTracker();
        UUID player = UUID.randomUUID();
        assertThat(tracker.tap(player, "normal", 0, 600, 2)).isFalse();
        assertThat(tracker.tap(player, "normal", 1000, 600, 2)).isFalse();
        assertThat(tracker.tap(player, "normal", 1100, 600, 2)).isTrue();
        assertThat(tracker.size()).isZero();
    }

    @Test
    void alternateGesturesAndOtherPlayersDoNotCompleteEachOthersSequences() {
        TapTracker tracker = new TapTracker();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        assertThat(tracker.tap(first, "normal", 0, 600, 2)).isFalse();
        assertThat(tracker.tap(second, "normal", 10, 600, 2)).isFalse();
        assertThat(tracker.tap(first, "sneaking", 20, 600, 2)).isFalse();
        assertThat(tracker.tap(first, "sneaking", 30, 600, 2)).isTrue();
        assertThat(tracker.tap(second, "normal", 40, 600, 2)).isTrue();
    }

    @Test
    void spectatorRequiresThreePressesWithAWindowBetweenEachPress() {
        TapTracker tracker = new TapTracker();
        UUID player = UUID.randomUUID();
        assertThat(tracker.tap(player, "spectator", 0, 600, 3)).isFalse();
        assertThat(tracker.tap(player, "spectator", 550, 600, 3)).isFalse();
        assertThat(tracker.tap(player, "spectator", 1100, 600, 3)).isTrue();
        tracker.tap(player, "spectator", 1200, 600, 3);
        tracker.remove(player);
        assertThat(tracker.tap(player, "spectator", 1250, 600, 3)).isFalse();
    }
}
