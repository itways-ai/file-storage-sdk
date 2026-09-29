package com.itways.assistant.attachment.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ObjectKeysTest {

    @Test
    void keysAreUniqueForTheSameInput() {
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            keys.add(ObjectKeys.newKey("p", "same.png"));
        }
        assertThat(keys).hasSize(1000);
    }

    @Test
    void prefixSegmentsAreSanitisedAndEmptyOnesDropped() {
        assertThat(ObjectKeys.normalisePrefix(null)).isEmpty();
        assertThat(ObjectKeys.normalisePrefix(" / ")).isEmpty();
        assertThat(ObjectKeys.normalisePrefix("/avatars//acc 1/")).isEqualTo("avatars/acc_1");
        assertThat(ObjectKeys.normalisePrefix("../..")).isEqualTo("__/__");
    }

    @Test
    void longNamesAreCutButKeepTheirExtension() {
        String longName = "a".repeat(300) + ".png";
        String cut = ObjectKeys.truncate(longName);
        assertThat(cut).hasSize(ObjectKeys.MAX_NAME_LENGTH).endsWith(".png");
        assertThat(ObjectKeys.truncate("short.png")).isEqualTo("short.png");
    }
}
