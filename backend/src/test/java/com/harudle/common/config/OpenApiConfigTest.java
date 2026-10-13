package com.harudle.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpenApiConfigTest {

    @Test
    void documentsNotificationReadPatchWithBearerAndCsrfOnlyForTheExactMethodAndPath() {
        Paths paths = new Paths();
        for (String path : List.of("/api/v1/notifications", "/api/v1/notifications/{notificationId}")) {
            PathItem pathItem = new PathItem();
            for (PathItem.HttpMethod method : List.of(PathItem.HttpMethod.GET, PathItem.HttpMethod.POST,
                    PathItem.HttpMethod.PUT, PathItem.HttpMethod.PATCH, PathItem.HttpMethod.DELETE)) {
                pathItem.operation(method, new Operation()
                        .addSecurityItem(new SecurityRequirement().addList("bearerAuth")));
            }
            paths.addPathItem(path, pathItem);
        }

        new OpenApiConfig().csrfSecurityCustomizer().customise(new OpenAPI().paths(paths));

        paths.forEach((path, pathItem) -> pathItem.readOperationsMap().forEach((method, operation) -> {
            boolean readPatch = path.equals("/api/v1/notifications/{notificationId}")
                    && method == PathItem.HttpMethod.PATCH;
            assertThat(operation.getSecurity()).singleElement().satisfies(requirement -> {
                if (readPatch) {
                    assertThat(requirement).containsOnlyKeys("bearerAuth", "csrfToken");
                } else {
                    assertThat(requirement).containsOnlyKeys("bearerAuth");
                }
            });
        }));
    }

    @Test
    void documentsLikeMutationsWithBearerAndCsrfWithoutChangingOtherMethods() {
        PathItem like = new PathItem();
        for (PathItem.HttpMethod method : List.of(PathItem.HttpMethod.GET, PathItem.HttpMethod.POST,
                PathItem.HttpMethod.PUT, PathItem.HttpMethod.PATCH, PathItem.HttpMethod.DELETE)) {
            like.operation(method, new Operation()
                    .addSecurityItem(new SecurityRequirement().addList("bearerAuth")));
        }
        new OpenApiConfig().csrfSecurityCustomizer().customise(new OpenAPI()
                .paths(new Paths().addPathItem("/api/v1/feeds/{feedId}/like", like)));

        for (PathItem.HttpMethod method : List.of(PathItem.HttpMethod.PUT, PathItem.HttpMethod.DELETE)) {
            assertThat(like.readOperationsMap().get(method).getSecurity()).singleElement()
                    .satisfies(requirement -> assertThat(requirement).containsOnlyKeys("bearerAuth", "csrfToken"));
        }
        for (PathItem.HttpMethod method : List.of(PathItem.HttpMethod.GET, PathItem.HttpMethod.POST,
                PathItem.HttpMethod.PATCH)) {
            assertThat(like.readOperationsMap().get(method).getSecurity()).singleElement()
                    .satisfies(requirement -> assertThat(requirement).containsOnlyKeys("bearerAuth"));
        }
    }

    @Test
    void requiresCsrfOnlyForCookieMutationsAndPreservesBearerSecurity() {
        Paths paths = new Paths();
        for (String path : List.of("/api/v1/auth/refresh", "/api/v1/auth/logout",
                "/api/v1/guest/session", "/api/v1/guest/diaries")) {
            paths.addPathItem(path, new PathItem().post(new Operation()).get(new Operation()));
        }
        for (String path : List.of("/api/v1/feeds", "/api/v1/diaries", "/api/v1/admin/categories")) {
            PathItem pathItem = new PathItem();
            for (PathItem.HttpMethod method : List.of(PathItem.HttpMethod.POST, PathItem.HttpMethod.PUT,
                    PathItem.HttpMethod.PATCH, PathItem.HttpMethod.DELETE)) {
                pathItem.operation(method, new Operation()
                        .addSecurityItem(new SecurityRequirement().addList("bearerAuth")));
            }
            paths.addPathItem(path, pathItem);
        }

        new OpenApiConfig().csrfSecurityCustomizer().customise(new OpenAPI().paths(paths));

        for (String path : List.of("/api/v1/auth/refresh", "/api/v1/auth/logout",
                "/api/v1/guest/session", "/api/v1/guest/diaries")) {
            assertThat(paths.get(path).getPost().getSecurity()).singleElement()
                    .satisfies(requirement -> assertThat(requirement).containsOnlyKeys("csrfToken"));
            assertThat(paths.get(path).getGet().getSecurity()).isNull();
        }
        for (String path : List.of("/api/v1/feeds", "/api/v1/diaries", "/api/v1/admin/categories")) {
            for (Operation operation : paths.get(path).readOperations()) {
                assertThat(operation.getSecurity()).singleElement()
                        .satisfies(requirement -> assertThat(requirement).containsOnlyKeys("bearerAuth"));
            }
        }
    }
}
