package com.itways.assistant.attachment.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * {@code cloudflare.r2.*} settings. Credentials are bound from the
 * environment by the consuming service; they never have defaults here.
 */
@Configuration
@ConfigurationProperties(prefix = "cloudflare.r2")
@Data
public class CloudFlareR2Config {
    private String accessKey;
    private String secretKey;
    private String accountId;
    private String publicDomain;
    private String bucket;
    private String publicBaseUrl;
    /**
     * Optional S3 endpoint override. When blank the R2 endpoint is derived
     * from the account id: {@code https://<accountId>.r2.cloudflarestorage.com}.
     * Meant for tests and S3-compatible stand-ins, not for production.
     */
    private String endpoint;
    /**
     * Optional default key prefix (for example {@code uploads}) used when the
     * caller does not pass one. Blank means objects go to the bucket root.
     */
    private String keyPrefix;

    /** The S3 endpoint the client talks to. */
    public String resolveEndpoint() {
        if (endpoint != null && !endpoint.isBlank()) {
            return endpoint.strip();
        }
        return String.format("https://%s.r2.cloudflarestorage.com", accountId);
    }
}
