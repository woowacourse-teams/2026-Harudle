package com.harudle.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OpenApiSchemaDescriptionConfigurationTest {

    private final OpenApiSchemaDescriptionConfiguration configuration =
            new OpenApiSchemaDescriptionConfiguration();

    @Test
    @DisplayName("날짜 및 정수 형식의 누락된 설명을 컴포넌트와 인라인 스키마에 채운다")
    void describesFormattedSchemas() {
        Schema<?> date = new StringSchema().format("date");
        Schema<?> dateTime = new Schema<>().types(Set.of("string")).format("date-time");
        Schema<?> int32 = new IntegerSchema().format("int32");
        Schema<?> int64 = new IntegerSchema().format("int64");
        Schema<?> arrayItem = new StringSchema().format("date-time");
        Schema<?> component = new ObjectSchema()
                .addProperty("date", date)
                .addProperty("dateTime", dateTime)
                .addProperty("int32", int32)
                .addProperty("int64", int64)
                .addProperty("times", new ArraySchema().items(arrayItem));
        Schema<?> queryDate = new StringSchema().format("date");
        Schema<?> bodyDate = new StringSchema().format("date");
        Schema<?> responseCount = new IntegerSchema().format("int32");
        Schema<?> responseHeader = new IntegerSchema().format("int64");
        ApiResponse response = new ApiResponse()
                .content(new Content().addMediaType("application/json", new MediaType().schema(responseCount)))
                .addHeaderObject("Retry-After", new Header().schema(responseHeader));
        Operation operation = new Operation()
                .addParametersItem(new Parameter().name("date").in("query").schema(queryDate))
                .requestBody(new RequestBody().content(new Content().addMediaType(
                        "application/json", new MediaType().schema(bodyDate))))
                .responses(new ApiResponses().addApiResponse("200", response));
        OpenAPI openApi = new OpenAPI()
                .components(new Components().addSchemas("Example", component))
                .path("/example", new PathItem().get(operation));

        configuration.missingFormatDescriptionCustomizer().customise(openApi);

        assertThat(date.getDescription()).isEqualTo("날짜 (YYYY-MM-DD)");
        assertThat(dateTime.getDescription()).isEqualTo("날짜와 시간 (RFC 3339)");
        assertThat(int32.getDescription()).isEqualTo("32비트 정수");
        assertThat(int64.getDescription()).isEqualTo("64비트 정수");
        assertThat(arrayItem.getDescription()).isEqualTo("날짜와 시간 (RFC 3339)");
        assertThat(queryDate.getDescription()).isEqualTo("날짜 (YYYY-MM-DD)");
        assertThat(bodyDate.getDescription()).isEqualTo("날짜 (YYYY-MM-DD)");
        assertThat(responseCount.getDescription()).isEqualTo("32비트 정수");
        assertThat(responseHeader.getDescription()).isEqualTo("64비트 정수");
    }

    @Test
    @DisplayName("명시된 설명과 스키마 타입은 변경하지 않는다")
    void preservesExplicitDescriptionsAndTypes() {
        Schema<?> explicitlyDescribed = new StringSchema().format("date")
                .description("일기를 작성한 날짜");
        Schema<?> wrongType = new StringSchema().format("int64");
        Schema<?> wrongFormat = new StringSchema().format("uuid");
        Schema<?> noFormat = new Schema<>().type("integer");
        Schema<?> component = new ObjectSchema()
                .addProperty("explicit", explicitlyDescribed)
                .addProperty("wrongType", wrongType)
                .addProperty("wrongFormat", wrongFormat)
                .addProperty("noFormat", noFormat);
        OpenAPI openApi = new OpenAPI().components(new Components().addSchemas("Example", component));

        configuration.missingFormatDescriptionCustomizer().customise(openApi);

        assertThat(explicitlyDescribed.getDescription()).isEqualTo("일기를 작성한 날짜");
        assertThat(explicitlyDescribed.getType()).isEqualTo("string");
        assertThat(explicitlyDescribed.getFormat()).isEqualTo("date");
        assertThat(wrongType.getDescription()).isNull();
        assertThat(wrongType.getType()).isEqualTo("string");
        assertThat(wrongType.getFormat()).isEqualTo("int64");
        assertThat(wrongFormat.getDescription()).isNull();
        assertThat(noFormat.getDescription()).isNull();
        assertThat(noFormat.getType()).isEqualTo("integer");
    }
}
