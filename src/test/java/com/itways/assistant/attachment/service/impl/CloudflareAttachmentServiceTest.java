package com.itways.assistant.attachment.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.itways.assistant.attachment.config.CloudFlareR2Config;
import com.itways.assistant.attachment.config.UploadAutoConfiguration;
import com.itways.assistant.attachment.dto.UploadResponse;
import com.itways.assistant.attachment.exception.AttachmentStorageException;
import com.itways.assistant.attachment.support.StubS3Server;
import com.itways.assistant.attachment.support.StubS3Server.Recorded;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Drives the real SDK v2 client, built exactly as in production, against a
 * local S3 stub, and checks what goes over the wire.
 */
class CloudflareAttachmentServiceTest {

    private static final String UUID_RE = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final byte[] PNG = new byte[] { (byte) 0x89, 'P', 'N', 'G', 1, 2, 3 };

    private StubS3Server stub;
    private S3Client client;
    private CloudFlareR2Config config;
    private CloudflareAttachmentService service;

    @BeforeEach
    void setUp() throws Exception {
        stub = new StubS3Server();
        config = new CloudFlareR2Config();
        config.setAccountId("test-account");
        config.setAccessKey("test-access-key");
        config.setSecretKey("test-secret-key");
        config.setBucket("test-bucket");
        config.setPublicDomain("https://cdn.example.test");
        config.setEndpoint(stub.endpoint());
        client = UploadAutoConfiguration.buildS3Client(config);
        service = new CloudflareAttachmentService(client, config);
    }

    @AfterEach
    void tearDown() {
        client.close();
        stub.close();
    }

    @Test
    void uploadPutsBytesUnderAUniquePrefixedKeyWithPathStyleAddressing() {
        UploadResponse response = service.upload("avatars/acc-1", "me.png", PNG);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getFileName()).isEqualTo("me.png");
        assertThat(response.getKey()).matches("avatars/acc-1/" + UUID_RE + "-me\\.png");

        Recorded put = single("PUT");
        assertThat(put.path()).isEqualTo("/test-bucket/" + response.getKey());
        assertThat(put.body()).isEqualTo(PNG);
        assertThat(put.header("Content-Length")).isEqualTo(String.valueOf(PNG.length));
    }

    @Test
    void theSameNameTwiceGivesTwoKeysSoNothingIsOverwritten() {
        UploadResponse first = service.upload("report.pdf", "a".getBytes(StandardCharsets.UTF_8));
        UploadResponse second = service.upload("report.pdf", "b".getBytes(StandardCharsets.UTF_8));

        assertThat(first.getKey()).isNotEqualTo(second.getKey());
        assertThat(first.getUrl()).isNotEqualTo(second.getUrl());
        assertThat(stub.requests()).extracting(Recorded::path)
                .containsExactly("/test-bucket/" + first.getKey(), "/test-bucket/" + second.getKey());
    }

    @Test
    void withoutAPrefixTheKeySitsAtTheBucketRootOrUnderTheConfiguredDefault() {
        assertThat(service.upload("a.txt", PNG).getKey()).matches(UUID_RE + "-a\\.txt");

        config.setKeyPrefix("uploads");
        assertThat(service.upload("a.txt", PNG).getKey()).matches("uploads/" + UUID_RE + "-a\\.txt");
    }

    @Test
    void prefixAndNameAreSanitisedSoAKeyCannotClimbOutOfItsPrefix() {
        UploadResponse response = service.upload("../../etc//x y", "../evil name?.png", PNG);

        assertThat(response.getKey()).matches("__/__/etc/x_y/" + UUID_RE + "-___evil_name_\\.png");
        assertThat(response.getKey()).doesNotContain("..");
    }

    @Test
    void contentTypeFollowsTheExtension() {
        service.upload("a.png", PNG);
        service.upload("b.JPG", PNG);
        service.upload("c.webp", PNG);
        service.upload("d.unknownext", PNG);

        assertThat(stub.requests()).extracting(r -> r.header("Content-Type"))
                .containsExactly("image/png", "image/jpeg", "image/webp", "application/octet-stream");
    }

    @Test
    void uploadSendsNoFlexibleChecksumsBecauseR2DoesNotAcceptThem() {
        service.upload("a.png", PNG);

        Recorded put = single("PUT");
        assertThat(put.headers().keySet())
                .noneMatch(h -> h.startsWith("x-amz-checksum-"))
                .doesNotContain("x-amz-sdk-checksum-algorithm", "x-amz-trailer");
        assertThat(put.header("Content-Encoding")).isNull();
        assertThat(put.header("x-amz-content-sha256")).doesNotStartWith("STREAMING-");
        assertThat(put.body()).isEqualTo(PNG);
        assertThat(put.header("Authorization")).contains("/auto/s3/aws4_request");
    }

    @Test
    void urlIsThePublicBaseFollowedByTheKey() {
        UploadResponse fromDomain = service.upload("a.png", PNG);
        assertThat(fromDomain.getUrl()).isEqualTo("https://cdn.example.test/" + fromDomain.getKey());

        config.setPublicDomain("cdn.example.test");
        UploadResponse bareDomain = service.upload("a.png", PNG);
        assertThat(bareDomain.getUrl()).isEqualTo("https://cdn.example.test/" + bareDomain.getKey());

        config.setPublicBaseUrl("https://files.example.test/base/");
        UploadResponse fromBase = service.upload("a.png", PNG);
        assertThat(fromBase.getUrl()).isEqualTo("https://files.example.test/base/" + fromBase.getKey());
    }

    @Test
    void missingPublicUrlSettingFailsBeforeAnythingIsStored() {
        config.setPublicDomain(null);

        assertThatThrownBy(() -> service.upload("a.png", PNG)).isInstanceOf(IllegalArgumentException.class);
        assertThat(stub.requests()).isEmpty();
    }

    @Test
    void emptyAndOversizedContentIsRejectedWithoutARequest() {
        assertThatThrownBy(() -> service.upload("a.png", new byte[0])).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.upload("a.png", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.upload("a.png", new byte[CloudflareAttachmentService.MAX_UPLOAD_BYTES + 1]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(stub.requests()).isEmpty();
    }

    @Test
    void deleteRemovesTheObjectByKey() {
        UploadResponse uploaded = service.upload("avatars", "a.png", PNG);

        service.delete(uploaded.getKey());

        Recorded delete = single("DELETE");
        assertThat(delete.path()).isEqualTo("/test-bucket/" + uploaded.getKey());
    }

    @Test
    void deleteRejectsBlankOrAbsoluteKeysWithoutARequest() {
        assertThatThrownBy(() -> service.delete(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.delete(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.delete("/abs")).isInstanceOf(IllegalArgumentException.class);
        assertThat(stub.requests()).isEmpty();
    }

    @Test
    void storageErrorsSurfaceAsAttachmentStorageException() {
        stub.failWith(403);

        assertThatThrownBy(() -> service.upload("a.png", PNG))
                .isInstanceOf(AttachmentStorageException.class)
                .isInstanceOf(RuntimeException.class)
                .hasMessageStartingWith("Upload failed");
        assertThatThrownBy(() -> service.delete("some/key"))
                .isInstanceOf(AttachmentStorageException.class)
                .hasMessageStartingWith("Delete failed");
    }

    private Recorded single(String method) {
        List<Recorded> matching = stub.requests().stream().filter(r -> r.method().equals(method)).toList();
        assertThat(matching).hasSize(1);
        return matching.get(0);
    }
}
