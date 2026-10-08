package com.itways.assistant.attachment.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Builds object keys of the form {@code [<prefix>/]<uuid>-<name>}: the random
 * UUID makes every key unique, so an upload can never overwrite an existing
 * object whatever name the caller passes, and the key cannot be guessed.
 */
public final class ObjectKeys {

    /** Longest name part kept in a key; S3 keys are capped at 1024 bytes. */
    static final int MAX_NAME_LENGTH = 128;

    private ObjectKeys() {
    }

    /**
     * @param prefix   optional path-like prefix; each {@code /}-separated segment
     *                 is sanitised to {@code [A-Za-z0-9_-]}, empty segments are
     *                 dropped, so it can never climb out with {@code ..}
     * @param safeName a name already passed through {@link FileUtils#safeFileName}
     */
    public static String newKey(String prefix, String safeName) {
        String normalisedPrefix = normalisePrefix(prefix);
        String name = truncate(safeName);
        String unique = UUID.randomUUID() + "-" + name;
        return normalisedPrefix.isEmpty() ? unique : normalisedPrefix + "/" + unique;
    }

    static String normalisePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return "";
        }
        List<String> segments = new ArrayList<>();
        for (String segment : prefix.split("/")) {
            if (!segment.isBlank()) {
                segments.add(segment.strip().replaceAll("[^a-zA-Z0-9\\-_]", "_"));
            }
        }
        return String.join("/", segments);
    }

    /** Keeps the extension and cuts the base name so the whole fits the limit. */
    static String truncate(String name) {
        if (name.length() <= MAX_NAME_LENGTH) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        String extension = dot > 0 ? name.substring(dot) : "";
        if (extension.length() >= MAX_NAME_LENGTH) {
            return name.substring(0, MAX_NAME_LENGTH);
        }
        return name.substring(0, MAX_NAME_LENGTH - extension.length()) + extension;
    }
}
