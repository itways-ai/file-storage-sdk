package com.itways.assistant.attachment.config;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

/**
 * Wires {@link com.itways.assistant.attachment.service.AttachmentService} from
 * {@code cloudflare.r2.*}. Registered as a Spring Boot auto-configuration
 * ({@code META-INF/spring/...AutoConfiguration.imports}), so having the jar on
 * the classpath is enough; {@code @EnableAttachment} imports the same class and
 * still works, alone or together with the auto-configuration (one set of beans).
 * <p>
 * Active only when the R2 credentials ({@code cloudflare.r2.access-key} and
 * {@code cloudflare.r2.secret-key}) are set: without them the S3 client cannot
 * be built, and an application that has the jar but does not use it must still
 * start.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "cloudflare.r2", name = { "access-key", "secret-key" })
@EnableConfigurationProperties
@ComponentScan("com.itways.assistant.attachment")
public class UploadAutoConfiguration {

    /** Name of the S3 client bean this SDK creates and uses. */
    public static final String S3_CLIENT_BEAN = "fileStorageS3Client";

    /** R2 ignores the region, but SigV4 needs one; Cloudflare documents {@code auto}. */
    static final Region R2_REGION = Region.of("auto");

    private final CloudFlareR2Config cloudflareR2Config;

    public UploadAutoConfiguration(CloudFlareR2Config cloudflareR2Config) {
        this.cloudflareR2Config = cloudflareR2Config;
    }

    @PostConstruct
    public void print() {
        log.info("Attachment SDK configuration initialized");
    }

    @Bean(name = S3_CLIENT_BEAN, destroyMethod = "close")
    public S3Client fileStorageS3Client() {
        return buildS3Client(cloudflareR2Config);
    }

    /**
     * The R2 client. Same behaviour as the v1 client it replaces (endpoint
     * override, region {@code auto}, path-style addressing, one plain PUT
     * body), made explicit where SDK v2 defaults differ:
     * <ul>
     * <li>checksums {@code WHEN_REQUIRED}: since 2.30 the SDK adds CRC
     * checksums (sent as aws-chunked trailers) to every upload by default,
     * which R2 has rejected or mishandled; this restores checksumming only
     * when an operation demands it;</li>
     * <li>chunked encoding off: the body is sent as-is with a Content-Length,
     * never as an aws-chunked signed stream, whatever the endpoint scheme;</li>
     * <li>the JDK HttpURLConnection transport (see pom.xml for why).</li>
     * </ul>
     */
    public static S3Client buildS3Client(CloudFlareR2Config config) {
        return S3Client.builder()
                .endpointOverride(URI.create(config.resolveEndpoint()))
                .region(R2_REGION)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(config.getAccessKey(), config.getSecretKey())))
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .chunkedEncodingEnabled(false)
                        .build())
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .build();
    }

}
