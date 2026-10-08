package com.itways.assistant.attachment.exception;

/**
 * The object store rejected or failed a request. Unchecked, as the v1
 * {@code RuntimeException} it replaces, so existing catch blocks still apply.
 */
public class AttachmentStorageException extends RuntimeException {

    public AttachmentStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
