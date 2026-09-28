package com.itways.assistant.attachment.service;

import com.itways.assistant.attachment.dto.UploadResponse;

public interface AttachmentService {

	/**
	 * Uploads under the configured default prefix ({@code cloudflare.r2.key-prefix},
	 * blank means the bucket root). See {@link #upload(String, String, byte[])}.
	 */
	UploadResponse upload(String fileName, byte[] bytes);

	/**
	 * Stores the bytes under a new, unique object key
	 * {@code [<keyPrefix>/]<uuid>-<sanitised fileName>} and returns its public
	 * URL ({@code <public base>/<key>}) and key. Two uploads never share a key,
	 * so an upload can never overwrite an existing object.
	 *
	 * @param keyPrefix optional path-like prefix, for example {@code avatars/123};
	 *                  each segment is sanitised, {@code null} or blank means none
	 * @throws IllegalArgumentException when the content is empty or over 50 MB
	 * @throws com.itways.assistant.attachment.exception.AttachmentStorageException
	 *                                  when the object store rejects the upload
	 */
	UploadResponse upload(String keyPrefix, String fileName, byte[] bytes);

	/**
	 * Deletes the object with this key (as returned in
	 * {@link UploadResponse#getKey()}). Deleting a key that does not exist
	 * succeeds, as in S3.
	 *
	 * @throws com.itways.assistant.attachment.exception.AttachmentStorageException
	 *                                  when the object store rejects the delete
	 */
	void delete(String key);
}
