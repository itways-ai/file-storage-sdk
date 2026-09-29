package com.itways.assistant.attachment;

import com.itways.assistant.attachment.config.UploadAutoConfiguration;
import java.lang.annotation.*;
import org.springframework.context.annotation.Import;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import(UploadAutoConfiguration.class)
public @interface EnableAttachment {
}
