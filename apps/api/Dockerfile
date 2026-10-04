# syntax=docker/dockerfile:1.7
# Insight Studio API (docs/deployment.md). Reproducible: base images pinned by digest, Maven builds with a
# fixed project.build.outputTimestamp (pom.xml), and the same inputs give the same application jar.
# Build from apps/api:  docker build -t insight-api:<git sha> apps/api

ARG JDK_IMAGE=eclipse-temurin:21-jdk-noble@sha256:70898f0f893a6b772a0f29834d8b022e3ac20b6a0c33a922973cf66342ef56be
ARG JRE_IMAGE=eclipse-temurin:21-jre-noble@sha256:22138efd69393501fccd8176ae16b01791ed71ff801b28f0359415389b17c766

FROM ${JDK_IMAGE} AS build
WORKDIR /src
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
# A Windows checkout may have CRLF line endings; the wrapper must run on Linux.
RUN sed -i 's/\r$//' mvnw && chmod +x mvnw
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline
COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -o -DskipTests package \
    && cp target/insight-api-*.jar /src/insight-api.jar \
    && java -Djarmode=tools -jar /src/insight-api.jar extract --layers --destination /extracted

FROM ${JRE_IMAGE}
# A fixed unprivileged user: no write access to the application, no shell login.
RUN useradd --system --uid 10001 --no-create-home --shell /usr/sbin/nologin insight
WORKDIR /app
# Layers from least to most often changed, so a code change only replaces the last one.
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./
USER 10001
# 8080: the API (behind the web proxy). 8081: actuator (probes, metrics) with the prod profile; never publish it.
EXPOSE 8080 8081
ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/urandom"
HEALTHCHECK --interval=15s --timeout=3s --start-period=90s --retries=3 \
    CMD curl -fsS "http://127.0.0.1:${MANAGEMENT_PORT:-8081}/actuator/health/liveness" > /dev/null || exit 1
ENTRYPOINT ["java", "-jar", "/app/insight-api.jar"]
