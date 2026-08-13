FROM amazoncorretto:21-alpine3.22-jdk AS builder

WORKDIR /workspace

COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle

RUN chmod +x gradlew \
    && ./gradlew --no-daemon dependencies

COPY src ./src

RUN ./gradlew --no-daemon clean bootJar -x test \
    && JAR_FILE="$(find build/libs -maxdepth 1 -type f -name '*.jar' ! -name '*-plain.jar' | head -n 1)" \
    && test -n "$JAR_FILE" \
    && cp "$JAR_FILE" /workspace/app.jar

FROM amazoncorretto:21-alpine3.22 AS runtime

RUN apk add --no-cache curl tini \
    && addgroup -S -g 10001 harudle \
    && adduser -S -D -H -u 10001 -G harudle harudle

WORKDIR /app

COPY --from=builder --chown=harudle:harudle /workspace/app.jar ./app.jar

USER harudle

EXPOSE 8080

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["/sbin/tini", "--"]
CMD ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
