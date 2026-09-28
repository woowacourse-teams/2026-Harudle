package com.harudle.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class OpenApiSchemaDescriptionConfiguration {

    @Bean
    OpenApiCustomizer missingFormatDescriptionCustomizer() {
        return OpenApiSchemaDescriptionConfiguration::describeFormattedSchemas;
    }

    private static void describeFormattedSchemas(OpenAPI openApi) {
        Set<Schema<?>> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Components components = openApi.getComponents();
        if (components != null) {
            if (components.getSchemas() != null) {
                components.getSchemas().values().forEach(schema -> visitSchema(schema, visited));
            }
            if (components.getParameters() != null) {
                components.getParameters().values().forEach(parameter -> visitParameter(parameter, visited));
            }
            if (components.getRequestBodies() != null) {
                components.getRequestBodies().values().forEach(body -> visitRequestBody(body, visited));
            }
            if (components.getResponses() != null) {
                components.getResponses().values().forEach(response -> visitResponse(response, visited));
            }
            if (components.getHeaders() != null) {
                components.getHeaders().values().forEach(header -> visitHeader(header, visited));
            }
        }
        if (openApi.getPaths() != null) {
            openApi.getPaths().values().forEach(path -> visitPath(path, visited));
        }
    }

    private static void visitPath(PathItem path, Set<Schema<?>> visited) {
        if (path.getParameters() != null) {
            path.getParameters().forEach(parameter -> visitParameter(parameter, visited));
        }
        path.readOperations().forEach(operation -> visitOperation(operation, visited));
    }

    private static void visitOperation(Operation operation, Set<Schema<?>> visited) {
        if (operation.getParameters() != null) {
            operation.getParameters().forEach(parameter -> visitParameter(parameter, visited));
        }
        visitRequestBody(operation.getRequestBody(), visited);
        if (operation.getResponses() != null) {
            operation.getResponses().values().forEach(response -> visitResponse(response, visited));
        }
    }

    private static void visitParameter(Parameter parameter, Set<Schema<?>> visited) {
        visitSchema(parameter.getSchema(), visited);
        visitContent(parameter.getContent(), visited);
    }

    private static void visitRequestBody(RequestBody body, Set<Schema<?>> visited) {
        if (body != null) {
            visitContent(body.getContent(), visited);
        }
    }

    private static void visitResponse(ApiResponse response, Set<Schema<?>> visited) {
        visitContent(response.getContent(), visited);
        if (response.getHeaders() != null) {
            response.getHeaders().values().forEach(header -> visitHeader(header, visited));
        }
    }

    private static void visitHeader(Header header, Set<Schema<?>> visited) {
        visitSchema(header.getSchema(), visited);
        visitContent(header.getContent(), visited);
    }

    private static void visitContent(Content content, Set<Schema<?>> visited) {
        if (content != null) {
            content.values().forEach(mediaType -> visitSchema(mediaType.getSchema(), visited));
        }
    }

    private static void visitSchema(Schema<?> schema, Set<Schema<?>> visited) {
        if (schema == null || !visited.add(schema)) {
            return;
        }

        if (schema.get$ref() == null && (schema.getDescription() == null || schema.getDescription().isBlank())) {
            String description = fallbackDescription(schema);
            if (description != null) {
                schema.setDescription(description);
            }
        }

        if (schema.getProperties() != null) {
            schema.getProperties().values().forEach(property -> visitSchema(property, visited));
        }
        visitSchema(schema.getItems(), visited);
        if (schema.getAdditionalProperties() instanceof Schema<?> additionalProperties) {
            visitSchema(additionalProperties, visited);
        }
        if (schema.getAllOf() != null) {
            schema.getAllOf().forEach(part -> visitSchema(part, visited));
        }
        if (schema.getAnyOf() != null) {
            schema.getAnyOf().forEach(part -> visitSchema(part, visited));
        }
        if (schema.getOneOf() != null) {
            schema.getOneOf().forEach(part -> visitSchema(part, visited));
        }
        visitSchema(schema.getNot(), visited);
    }

    private static String fallbackDescription(Schema<?> schema) {
        String format = schema.getFormat();
        if (hasType(schema, "string")) {
            if ("date".equals(format)) {
                return "날짜 (YYYY-MM-DD)";
            }
            if ("date-time".equals(format)) {
                return "날짜와 시간 (RFC 3339)";
            }
        }
        if (hasType(schema, "integer")) {
            if ("int32".equals(format)) {
                return "32비트 정수";
            }
            if ("int64".equals(format)) {
                return "64비트 정수";
            }
        }
        return null;
    }

    private static boolean hasType(Schema<?> schema, String expected) {
        if (expected.equals(schema.getType())) {
            return true;
        }
        Set<String> types = schema.getTypes();
        return schema.getType() == null && types != null && types.contains(expected)
                && types.stream().allMatch(type -> type == null || expected.equals(type));
    }
}
