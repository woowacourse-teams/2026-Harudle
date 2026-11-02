package com.harudle.notification.presentation;

import com.harudle.TestcontainersConfiguration;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "HARUDLE_API_DOCUMENTATION_ENABLED=true")
@AutoConfigureMockMvc
class NotificationOpenApiTest {
    @Autowired private MockMvc mockMvc;

    @Test
    void documentsAuthenticatedListWithGlobalUnreadCountAndIntegrationError() throws Exception {
        String operation = "$.paths['/api/v1/notifications'].get";
        String expiry = "$.components.schemas.NotificationActorResponse.properties.profileImageUrlExpiresAt";
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath(operation + ".description").value(containsString("좋아요 취소 시 숨기며")))
                .andExpect(jsonPath(operation + ".description").value(containsString("피드 작성자가 탈퇴한 알림")))
                .andExpect(jsonPath(operation + ".description").value(containsString("COMMENT 알림은 댓글이 삭제돼도 유지")))
                .andExpect(jsonPath(operation + ".security[0].bearerAuth").isArray())
                .andExpect(jsonPath(operation + ".security[0].csrfToken").doesNotExist())
                .andExpect(jsonPath(operation + ".responses['200'].content['application/json'].schema['$ref']")
                        .value("#/components/schemas/NotificationPageResponse"))
                .andExpect(jsonPath(operation + ".responses['400'].content['application/problem+json']"
                        + ".examples.INVALID_CURSOR.value.code").value("INVALID_CURSOR"))
                .andExpect(jsonPath(operation + ".responses['503'].content['application/problem+json']"
                        + ".examples.NOTIFICATION_UNAVAILABLE.value.code").value("NOTIFICATION_UNAVAILABLE"))
                .andExpect(jsonPath("$.components.schemas.NotificationPageResponse.properties.unreadCount.format")
                        .value("int64"))
                .andExpect(jsonPath("$.components.schemas.NotificationResponse.properties.read.type").value("boolean"))
                .andExpect(jsonPath("$.components.schemas.NotificationResponse.properties.type.enum[0]").value("LIKE"))
                .andExpect(jsonPath("$.components.schemas.NotificationResponse.properties.type.enum[1]").value("COMMENT"))
                .andExpect(jsonPath(expiry + ".format")
                        .value("date-time"))
                .andExpect(jsonPath(expiry + ".nullable")
                        .value(true));
    }

    @Test
    void documentsSingleReadPatchWithBearerAndCsrfAndNo204Body() throws Exception {
        String operation = "$.paths['/api/v1/notifications/{notificationId}'].patch";
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath(operation + ".description").value(containsString("좋아요가 취소된 LIKE 알림은 404")))
                .andExpect(jsonPath(operation + ".description").value(containsString("피드 작성자가 탈퇴한 알림도 404")))
                .andExpect(jsonPath(operation + ".security[0].bearerAuth").isArray())
                .andExpect(jsonPath(operation + ".security[0].csrfToken").isArray())
                .andExpect(jsonPath(operation + ".responses['204']").exists())
                .andExpect(jsonPath(operation + ".responses['204'].content").doesNotExist())
                .andExpect(jsonPath(operation + ".responses['403'].content['application/problem+json']"
                        + ".examples.FORBIDDEN.value.code").value("FORBIDDEN"))
                .andExpect(jsonPath(operation + ".responses['403'].content['application/problem+json']"
                        + ".examples.INVALID_CSRF_TOKEN.value.code").value("INVALID_CSRF_TOKEN"))
                .andExpect(jsonPath(operation + ".responses['404'].content['application/problem+json']"
                        + ".examples.NOTIFICATION_NOT_FOUND.value.code").value("NOTIFICATION_NOT_FOUND"));
    }
}
