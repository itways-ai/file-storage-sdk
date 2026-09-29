package com.itways.assistant.attachment.utils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import org.springframework.web.multipart.MultipartFile;

public class FileUtils {

    /**
     * Types this SDK must get right on every OS: {@code Files.probeContentType}
     * depends on the host's MIME database, which varies between machines and
     * container images; a miss would store an image as octet-stream.
     */
    private static final Map<String, String> KNOWN_TYPES = Map.ofEntries(
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("pdf", "application/pdf"),
            Map.entry("txt", "text/plain"),
            Map.entry("json", "application/json"));

    public static String detectContentType(String fileName) {
        String contentType = "application/octet-stream";
        if (fileName == null) {
            return contentType;
        }
        int dot = fileName.lastIndexOf('.');
        if (dot >= 0) {
            String known = KNOWN_TYPES.get(fileName.substring(dot + 1).toLowerCase(Locale.ROOT));
            if (known != null) {
                return known;
            }
        }
        try {
            String probed = Files.probeContentType(Path.of(fileName));
            if (probed != null) {
                contentType = probed;
            }
        } catch (Exception ignored) {
        }
        return contentType;
    }

    public static MultipartFile toMultipartFile(byte[] bytes, String fileName) {
        String safeName = safeFileName(fileName);
        String contentType = detectContentType(safeName);
        return new ByteArrayMultipartFile(bytes, safeName, safeName, contentType);
    }

    public static String safeFileName(String name) {
        if (name == null || name.isBlank()) {
            return "file_" + System.currentTimeMillis();
        }
        int dotIndex = name.lastIndexOf('.');
        if (dotIndex > 0) {
            String baseName = name.substring(0, dotIndex);
            String extension = name.substring(dotIndex + 1); // without the dot
            String safeBase = baseName.replaceAll("[^a-zA-Z0-9\\-_]", "_");
            String safeExt = extension.replaceAll("[^a-zA-Z0-9]", "");
            return safeBase + "." + safeExt;
        }
        // No extension — sanitize the whole name
        return name.replaceAll("[^a-zA-Z0-9\\-_]", "_");
    }
}
