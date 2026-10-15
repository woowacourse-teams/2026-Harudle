package com.harudle.support;

import com.harudle.HarudleApplication;
import com.harudle.TestcontainersConfiguration;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@SpringBootTest(classes = HarudleApplication.class,
        properties = "harudle.profile.image-base-url=https://www.harudle.com/profile-images/v1")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
public @interface MockMvcIntegrationTest {
}
