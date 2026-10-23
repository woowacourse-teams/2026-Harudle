FROM amazoncorretto:21-alpine3.22-jdk AS dependencies

WORKDIR /workspace

COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle

RUN chmod +x gradlew \
    && ./gradlew --no-daemon resolveProductionDependencies

FROM dependencies AS builder

COPY src ./src

RUN gradle_build_started="$(date +%s)" \
    && ./gradlew --no-daemon bootJar -x test \
    && echo "[build-metrics] backend_gradle_bootjar_seconds=$(($(date +%s) - gradle_build_started))" \
    && JAR_FILE="$(find build/libs -maxdepth 1 -type f -name '*.jar' ! -name '*-plain.jar' | head -n 1)" \
    && test -n "$JAR_FILE" \
    && cp "$JAR_FILE" /workspace/app.jar

FROM amazoncorretto:21-alpine3.22 AS runtime-base

RUN apk add --no-cache curl tini libwebp-tools \
    && addgroup -S -g 10001 harudle \
    && adduser -S -D -H -u 10001 -G harudle harudle

WORKDIR /app

USER harudle

EXPOSE 8080

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["/sbin/tini", "--"]
CMD ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]

# CodeBuild compiles on the host and only packages the resulting JAR here.
FROM runtime-base AS codebuild
COPY --chown=harudle:harudle build/codebuild/app.jar ./app.jar

# Keep the default target usable by docker compose build and local Docker builds.
FROM runtime-base AS runtime
COPY --from=builder --chown=harudle:harudle /workspace/app.jar ./app.jar
