# syntax=docker/dockerfile:1.7
#
# One image recipe for every service. Pick the module with SERVICE:
#
#   docker build --build-arg SERVICE=ledger-service -t ledger/ledger-service services
#
# Stage 1 builds the module and what it depends on, with the Maven
# repository cached between builds. Stage 2 splits the jar into Spring Boot's
# layers, so a code change rebuilds a few kilobytes of image instead of the
# hundred megabytes of dependencies under it. Stage 3 is the runtime: a JRE,
# a non-root user, and nothing else.

ARG JAVA_VERSION=21

FROM eclipse-temurin:${JAVA_VERSION}-jdk AS build
ARG SERVICE
WORKDIR /workspace
# Sources only (see .dockerignore). Maven needs every module's pom.xml to
# read the reactor, and -pl/-am then builds just this service and the
# modules it depends on.
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -ntp -pl ${SERVICE} -am -DskipTests -Djacoco.skip=true package \
 && cp ${SERVICE}/target/${SERVICE}-*.jar /workspace/application.jar

FROM eclipse-temurin:${JAVA_VERSION}-jre AS layers
WORKDIR /layers
COPY --from=build /workspace/application.jar application.jar
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

FROM eclipse-temurin:${JAVA_VERSION}-jre
ARG SERVICE
LABEL org.opencontainers.image.title="${SERVICE}" \
      org.opencontainers.image.source="https://github.com/amishpr/ledger-bank-system"
RUN groupadd --system --gid 1001 spring && useradd --system --uid 1001 --gid spring spring
WORKDIR /application
COPY --from=layers --chown=spring:spring /layers/extracted/dependencies/ ./
COPY --from=layers --chown=spring:spring /layers/extracted/spring-boot-loader/ ./
COPY --from=layers --chown=spring:spring /layers/extracted/snapshot-dependencies/ ./
COPY --from=layers --chown=spring:spring /layers/extracted/application/ ./
USER spring
# Size the heap from the container's memory limit, not the host's, and let
# the platform restart a process that runs out instead of limping along.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError" \
    LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs
ENTRYPOINT ["java", "-jar", "application.jar"]
