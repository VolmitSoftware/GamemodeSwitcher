package com.volmit.gsw.gameplay;

import com.volmit.gsw.config.FeedbackConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PersonalFeedbackTest {
    @Test
    void defaultChoiceTracksServerChangesAndCannotEnableServerDisabledChannels() {
        FeedbackConfig enabled = new FeedbackConfig(true, true, true, 50, true, "minecraft:ui.button.click", 0.7F, 1.2F);
        FeedbackConfig disabled = new FeedbackConfig(false, false, false, 50, false, "minecraft:ui.button.click", 0.7F, 1.2F);
        assertThat(PersonalFeedback.DEFAULT.apply(enabled)).isEqualTo(enabled);
        assertThat(PersonalFeedback.DEFAULT.apply(disabled)).isEqualTo(disabled);
        assertThat(PersonalFeedback.SILENT.apply(enabled)).isEqualTo(disabled);
        PersonalFeedback chatMuted = PersonalFeedback.DEFAULT.toggle(PersonalFeedback.Channel.CHAT);
        assertThat(chatMuted.apply(enabled).chatEnabled()).isFalse();
        assertThat(chatMuted.apply(enabled).actionBarEnabled()).isTrue();
        assertThat(chatMuted.apply(enabled).titleEnabled()).isTrue();
        assertThat(chatMuted.apply(enabled).soundEnabled()).isTrue();
        assertThat(chatMuted.toggle(PersonalFeedback.Channel.CHAT)).isEqualTo(PersonalFeedback.DEFAULT);
    }
}
