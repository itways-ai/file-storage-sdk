package com.itways.assistant.attachment.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.itways.assistant.attachment.EnableAttachment;
import com.itways.assistant.attachment.dto.UploadResponse;
import com.itways.assistant.attachment.service.AttachmentService;
import com.itways.assistant.attachment.support.StubS3Server;

import software.amazon.awssdk.services.s3.S3Client;

/** {@code @EnableAttachment} wires a working service from {@code cloudflare.r2.*} properties. */
class EnableAttachmentContextTest {

	@EnableAttachment
	static class ConsumerApp {
	}

	@Test
	void enableAttachmentBindsPropertiesAndUploadsThroughTheConfiguredEndpoint() throws Exception {
		try (StubS3Server stub = new StubS3Server()) {
			new ApplicationContextRunner()
					.withUserConfiguration(ConsumerApp.class)
					.withPropertyValues(
							"cloudflare.r2.account-id=test-account",
							"cloudflare.r2.access-key=test-access-key",
							"cloudflare.r2.secret-key=test-secret-key",
							"cloudflare.r2.bucket=test-bucket",
							"cloudflare.r2.public-domain=https://cdn.example.test",
							"cloudflare.r2.endpoint=" + stub.endpoint())
					.run(context -> {
						assertThat(context).hasNotFailed();
						assertThat(context).hasSingleBean(AttachmentService.class);
						assertThat(context).hasBean(UploadAutoConfiguration.S3_CLIENT_BEAN);

						UploadResponse response = context.getBean(AttachmentService.class)
								.upload("x", "a.png", new byte[] { 1 });
						assertThat(response.getUrl()).startsWith("https://cdn.example.test/x/");
						assertThat(stub.requests()).singleElement()
								.satisfies(r -> assertThat(r.path()).startsWith("/test-bucket/x/"));
					});
		}
	}

	@Test
	void withoutAnEndpointOverrideTheR2EndpointComesFromTheAccountId() {
		new ApplicationContextRunner()
				.withUserConfiguration(ConsumerApp.class)
				.withPropertyValues(
						"cloudflare.r2.account-id=r2disabled",
						"cloudflare.r2.access-key=r2disabled",
						"cloudflare.r2.secret-key=r2disabled",
						"cloudflare.r2.bucket=media-service",
						"cloudflare.r2.public-domain=https://cdn.example.test")
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context.getBean(CloudFlareR2Config.class).resolveEndpoint())
							.isEqualTo("https://r2disabled.r2.cloudflarestorage.com");
					S3Client client = context.getBean(UploadAutoConfiguration.S3_CLIENT_BEAN, S3Client.class);
					assertThat(client.serviceClientConfiguration().endpointOverride())
							.hasValueSatisfying(uri -> assertThat(uri.toString())
									.isEqualTo("https://r2disabled.r2.cloudflarestorage.com"));
					assertThat(client.serviceClientConfiguration().region().id()).isEqualTo("auto");
				});
	}
}
