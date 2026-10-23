package com.harudle.feed.presentation;

import com.harudle.TestcontainersConfiguration;
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
class FeedLikeOpenApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void documentsBothLikeOperationsTheirSecurityAndErrorContracts() throws Exception {
        for (String method : new String[]{"put", "delete"}) {
            String operation = "$.paths['/api/v1/feeds/{feedId}/like']." + method;
            mockMvc.perform(get("/v3/api-docs"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath(operation + ".security[0].bearerAuth").isArray())
                    .andExpect(jsonPath(operation + ".security[0].csrfToken").isArray())
                    .andExpect(jsonPath(operation + ".responses['200'].content['application/json'].schema['$ref']")
                            .value("#/components/schemas/FeedLikeResponse"))
                    .andExpect(jsonPath(operation + ".responses['400'].content['application/problem+json']"
                            + ".examples.VALIDATION_ERROR.value.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath(operation + ".responses['401'].content['application/problem+json']"
                            + ".examples.UNAUTHORIZED.value.code").value("UNAUTHORIZED"))
                    .andExpect(jsonPath(operation + ".responses['403'].content['application/problem+json']"
                            + ".examples.INVALID_CSRF_TOKEN.value.code").value("INVALID_CSRF_TOKEN"))
                    .andExpect(jsonPath(operation + ".responses['404'].content['application/problem+json']"
                            + ".examples.FEED_NOT_FOUND.value.code").value("FEED_NOT_FOUND"))
                    .andExpect(jsonPath("$.components.schemas.FeedLikeResponse.properties.likedByMe.type")
                            .value("boolean"))
                    .andExpect(jsonPath("$.components.schemas.FeedLikeResponse.properties.likeCount.minimum")
                            .value(0));
        }
    }
}
