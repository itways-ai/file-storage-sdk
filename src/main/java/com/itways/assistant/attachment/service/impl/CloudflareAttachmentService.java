package com.itways.assistant.attachment.service.impl;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.itways.assistant.attachment.config.CloudFlareR2Config;
import com.itways.assistant.attachment.config.UploadAutoConfiguration;
import com.itways.assistant.attachment.dto.UploadResponse;
import com.itways.assistant.attachment.exception.AttachmentStorageException;
import com.itways.assistant.attachment.service.AttachmentService;
import com.itways.assistant.attachment.utils.FileUtils;
import com.itways.assistant.attachment.utils.ObjectKeys;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Service
public class CloudflareAttachmentService implements AttachmentService {

	static final int MAX_UPLOAD_BYTES = 50 * 1024 * 1024; // 50 MB hard limit

	private final S3Client s3Client;
	private final CloudFlareR2Config config;

	public CloudflareAttachmentService(@Qualifier(UploadAutoConfiguration.S3_CLIENT_BEAN) S3Client s3Client,
			CloudFlareR2Config config) {
		this.s3Client = s3Client;
		this.config = config;
	}

	@Override
	public UploadResponse upload(String fileName, byte[] bytes) {
		return upload(config.getKeyPrefix(), fileName, bytes);
	}

	@Override
	public UploadResponse upload(String keyPrefix, String fileName, byte[] bytes) {
		if (bytes == null || bytes.length == 0) {
			throw new IllegalArgumentException("File content must not be empty");
		}
		if (bytes.length > MAX_UPLOAD_BYTES) {
			throw new IllegalArgumentException("File size exceeds the maximum allowed limit of 50MB");
		}
		String safeFileName = FileUtils.safeFileName(fileName);
		String contentType = FileUtils.detectContentType(safeFileName);
		String key = ObjectKeys.newKey(keyPrefix, safeFileName);
		// Resolved before the upload so a missing public URL setting fails
		// without leaving an unreachable object behind.
		String publicUrl = buildPublicUrl(key);

		try {
			s3Client.putObject(PutObjectRequest.builder()
					.bucket(config.getBucket())
					.key(key)
					.contentType(contentType)
					.contentLength((long) bytes.length)
					.build(), RequestBody.fromBytes(bytes));
		} catch (SdkException e) {
			throw new AttachmentStorageException("Upload failed: " + e.getMessage(), e);
		}
		return new UploadResponse(safeFileName, publicUrl, true, "Uploaded successfully", key);
	}

	@Override
	public void delete(String key) {
		if (key == null || key.isBlank() || key.startsWith("/")) {
			throw new IllegalArgumentException("Object key must be a non-blank relative key");
		}
		try {
			s3Client.deleteObject(DeleteObjectRequest.builder()
					.bucket(config.getBucket())
					.key(key)
					.build());
		} catch (SdkException e) {
			throw new AttachmentStorageException("Delete failed: " + e.getMessage(), e);
		}
	}

	private String buildPublicUrl(String key) {
		// Prefer publicBaseUrl if present, fallback to publicDomain
		String base;
		if (config.getPublicBaseUrl() != null && !config.getPublicBaseUrl().isBlank()) {
			base = config.getPublicBaseUrl().strip();
		} else if (config.getPublicDomain() != null && !config.getPublicDomain().isBlank()) {
			String domain = config.getPublicDomain().strip();
			base = domain.startsWith("http://") || domain.startsWith("https://") ? domain : "https://" + domain;
		} else {
			throw new IllegalArgumentException("No publicBaseUrl or publicDomain configured for Cloudflare R2");
		}
		while (base.endsWith("/")) {
			base = base.substring(0, base.length() - 1);
		}
		return base + "/" + key;
	}

}
