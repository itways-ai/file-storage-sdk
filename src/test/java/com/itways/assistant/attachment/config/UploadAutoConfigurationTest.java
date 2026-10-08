package com.itways.assistant.attachment.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.itways.assistant.attachment.EnableAttachment;
import com.itways.assistant.attachment.dto.UploadResponse;
import com.itways.assistant.attachment.service.AttachmentService;
import com.itways.assistant.attachment.support.StubS3Server;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * The auto-configuration registers by itself from
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * (2.0.0 had the file under {@code spring/}, so only {@code @EnableAttachment}
 * wired anything), coexists with {@code @EnableAttachment} without duplicate
 * beans, and backs off when the R2 credentials are not configured.
 */
class UploadAutoConfigurationTest {

    /** The property set auth-service ships (its stand-in values when R2 is not configured). */
    private static final String[] AUTH_SERVICE_PROPERTIES = {
            "cloudflare.r2.account-id=r2disabled",
            "cloudflare.r2.access-key=r2disabled",
            "cloudflare.r2.secret-key=r2disabled",
            "cloudflare.r2.bucket=media-service",
            "cloudflare.r2.public-domain=https://cdn.example.test" };

    /** A plain Boot application: auto-configuration only, no {@code @EnableAttachment}. */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class AutoConfiguredApp {
    }

    /** auth-service's shape: {@code @EnableAttachment} on a Boot application. */
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EnableAttachment
    static class EnableAttachmentApp {
    }

    @EnableAttachment
    static class EnableAttachmentOnly {
    }

    /** A consumer that needs the service, as auth-service's profile service does. */
    @Configuration(proxyBeanMethods = false)
    static class ServiceConsumer {
        @Bean
        String avatarStore(AttachmentService attachmentService) {
            return "uses " + attachmentService.getClass().getSimpleName();
        }
    }

    @Test
    void theImportsFileListsTheAutoConfiguration() {
        assertThat(ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader()).getCandidates())
                .contains(UploadAutoConfiguration.class.getName());
    }

    @Test
    void enableAutoConfigurationAloneWiresAWorkingServiceWithoutEnableAttachment() throws Exception {
        try (StubS3Server stub = new StubS3Server()) {
            new ApplicationContextRunner()
                    .withUserConfiguration(AutoConfiguredApp.class)
                    .withPropertyValues(AUTH_SERVICE_PROPERTIES)
                    .withPropertyValues("cloudflare.r2.endpoint=" + stub.endpoint())
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertOneOfEach(context);

                        UploadResponse response = context.getBean(AttachmentService.class)
                                .upload("x", "a.png", new byte[] { 1 });
                        assertThat(response.getUrl()).startsWith("https://cdn.example.test/x/");
                        assertThat(stub.requests()).singleElement()
                                .satisfies(r -> assertThat(r.path()).startsWith("/media-service/x/"));
                    });
        }
    }

    @Test
    void theAutoConfigurationClassAloneExposesTheSameBeansAsEnableAttachment() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(UploadAutoConfiguration.class))
                .withPropertyValues(AUTH_SERVICE_PROPERTIES)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    // The runner itself registers a class given as AutoConfigurations
                    // under both its short and its fully qualified name, so only the
                    // beans the configuration creates are counted here; a real
                    // application (above and below) has one of the class too.
                    assertOneServiceClientAndConfig(context);
                });
    }

    @Test
    void enableAttachmentOnABootApplicationStillGivesExactlyOneBeanOfEachType() {
        new ApplicationContextRunner()
                .withUserConfiguration(EnableAttachmentApp.class)
                .withPropertyValues(AUTH_SERVICE_PROPERTIES)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertOneOfEach(context);
                });
    }

    @Test
    void enableAttachmentPlusTheAutoConfigurationClassGivesExactlyOneBeanOfEachType() {
        new ApplicationContextRunner()
                .withUserConfiguration(EnableAttachmentOnly.class)
                .withConfiguration(AutoConfigurations.of(UploadAutoConfiguration.class))
                .withPropertyValues(AUTH_SERVICE_PROPERTIES)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertOneOfEach(context);
                });
    }

    @Test
    void withoutR2PropertiesTheAutoConfigurationBacksOffAndTheApplicationStarts() {
        new ApplicationContextRunner()
                .withUserConfiguration(AutoConfiguredApp.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertNoAttachmentBeans(context);
                });
    }

    @Test
    void bothCredentialsAreNeededForTheAutoConfigurationToApply() {
        new ApplicationContextRunner()
                .withUserConfiguration(AutoConfiguredApp.class)
                .withPropertyValues("cloudflare.r2.access-key=key-only", "cloudflare.r2.bucket=media-service")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertNoAttachmentBeans(context);
                });
        new ApplicationContextRunner()
                .withUserConfiguration(AutoConfiguredApp.class)
                .withPropertyValues("cloudflare.r2.secret-key=secret-only")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertNoAttachmentBeans(context);
                });
    }

    @Test
    void enableAttachmentWithoutCredentialsGivesNoServiceSoAConsumerStillFailsAtStartUp() {
        // 2.0.0 failed inside the S3 client ("Access key ID cannot be blank");
        // now the configuration backs off and the missing service is reported.
        new ApplicationContextRunner()
                .withUserConfiguration(EnableAttachmentOnly.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertNoAttachmentBeans(context);
                });
        new ApplicationContextRunner()
                .withUserConfiguration(EnableAttachmentOnly.class, ServiceConsumer.class)
                .run(context -> assertThat(context).getFailure()
                        .rootCause()
                        .isInstanceOf(NoSuchBeanDefinitionException.class)
                        .hasMessageContaining(AttachmentService.class.getName()));
    }

    @Test
    void credentialsThatAreSetButBlankStillFailStartUpAsBefore() {
        // Configured-but-empty is a mistake, not "off": same failure as 2.0.0.
        new ApplicationContextRunner()
                .withUserConfiguration(AutoConfiguredApp.class)
                .withPropertyValues("cloudflare.r2.access-key=", "cloudflare.r2.secret-key=")
                .run(context -> assertThat(context).getFailure()
                        .rootCause()
                        .hasMessageContaining("Access key ID cannot be blank"));
    }

    private static void assertOneOfEach(AssertableApplicationContext context) {
        assertThat(context).hasSingleBean(UploadAutoConfiguration.class);
        assertOneServiceClientAndConfig(context);
    }

    private static void assertOneServiceClientAndConfig(AssertableApplicationContext context) {
        assertThat(context).hasSingleBean(CloudFlareR2Config.class);
        assertThat(context).hasSingleBean(AttachmentService.class);
        assertThat(context).hasSingleBean(S3Client.class);
        assertThat(context).hasBean(UploadAutoConfiguration.S3_CLIENT_BEAN);
    }

    private static void assertNoAttachmentBeans(AssertableApplicationContext context) {
        assertThat(context).doesNotHaveBean(UploadAutoConfiguration.class);
        assertThat(context).doesNotHaveBean(CloudFlareR2Config.class);
        assertThat(context).doesNotHaveBean(AttachmentService.class);
        assertThat(context).doesNotHaveBean(UploadAutoConfiguration.S3_CLIENT_BEAN);
    }
}
