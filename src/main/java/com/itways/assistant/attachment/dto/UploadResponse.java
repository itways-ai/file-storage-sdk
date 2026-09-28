package com.itways.assistant.attachment.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class UploadResponse {
    /** The sanitised file name the caller passed (without the unique part). */
    private String fileName;
    /** Public URL: {@code <public base>/<key>}. */
    private String url;
    private boolean success;
    private String message;
    /** The object key in the bucket; pass it to {@code AttachmentService.delete}. */
    private String key;

    /** The 1.x constructor, kept for source compatibility. */
    public UploadResponse(String fileName, String url, boolean success, String message) {
        this(fileName, url, success, message, null);
    }
}
