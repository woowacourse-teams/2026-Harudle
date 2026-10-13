package com.harudle.comment.presentation;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = "HARUDLE_API_DOCUMENTATION_ENABLED=true")
@AutoConfigureMockMvc
class CommentOpenApiTest {
    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

    @Autowired private MockMvc mockMvc;

    @Test
    void documentsPublicListSchemaAndCursorErrors() throws Exception {
        String operation = "$.paths['/api/v1/feeds/{feedId}/comments'].get";
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath(operation + ".security").doesNotExist())
                .andExpect(jsonPath(operation + ".responses['200'].content['application/json'].schema['$ref']")
                        .value("#/components/schemas/CommentPageResponse"))
                .andExpect(jsonPath(operation + ".responses['400'].content['application/problem+json']"
                        + ".examples.INVALID_CURSOR.value.code").value("INVALID_CURSOR"))
                .andExpect(jsonPath(operation + ".responses['503'].content['application/problem+json']"
                        + ".examples.COMMENT_UNAVAILABLE.value.code").value("COMMENT_UNAVAILABLE"));
    }

    @Test
    void documentsAuthenticatedMutationsWithCsrfAndSuccessStatus() throws Exception {
        String create = "$.paths['/api/v1/feeds/{feedId}/comments'].post";
        String remove = "$.paths['/api/v1/comments/{commentId}'].delete";
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath(create + ".security[0].bearerAuth").isArray())
                .andExpect(jsonPath(create + ".security[0].csrfToken").isArray())
                .andExpect(jsonPath(create + ".responses['201'].content['application/json'].schema['$ref']")
                        .value("#/components/schemas/CommentResponse"))
                .andExpect(jsonPath(create + ".responses['403'].content['application/problem+json']"
                        + ".examples.INVALID_CSRF_TOKEN.value.code").value("INVALID_CSRF_TOKEN"))
                .andExpect(jsonPath(remove + ".security[0].bearerAuth").isArray())
                .andExpect(jsonPath(remove + ".security[0].csrfToken").isArray())
                .andExpect(jsonPath(remove + ".responses['204']").exists())
                .andExpect(jsonPath(remove + ".responses['204'].content").doesNotExist())
                .andExpect(jsonPath(remove + ".responses['403'].content['application/problem+json']"
                        + ".examples.FORBIDDEN.value.code").value("FORBIDDEN"))
                .andExpect(jsonPath(remove + ".responses['404'].content['application/problem+json']"
                        + ".examples.COMMENT_NOT_FOUND.value.code").value("COMMENT_NOT_FOUND"))
                .andExpect(jsonPath("$.components.schemas.CommentResponse.properties.isMine.type").value("boolean"))
                .andExpect(jsonPath("$.components.schemas.CommentResponse.properties.author['$ref']")
                        .value("#/components/schemas/CommentAuthorResponse"));
    }

    @Test
    void documentsWithdrawnAuthorFieldsAsNullableWhileKeepingUuidAndUriStringFormats() throws Exception {
        String author = "$.components.schemas.CommentAuthorResponse.properties";
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.components.schemas.CommentResponse.properties.author['$ref']")
                        .value("#/components/schemas/CommentAuthorResponse"))
                .andExpect(jsonPath(author + ".id.type").value("string"))
                .andExpect(jsonPath(author + ".id.nullable").value(true))
                .andExpect(jsonPath(author + ".id.format").value("uuid"))
                .andExpect(jsonPath(author + ".nickname.type").value("string"))
                .andExpect(jsonPath(author + ".profileImageUrl.type").value("string"))
                .andExpect(jsonPath(author + ".profileImageUrl.nullable").value(true))
                .andExpect(jsonPath(author + ".profileImageUrl.format").value("uri"));
    }
}
