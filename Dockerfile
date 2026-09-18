# Multi-stage Dockerfile for CoreBank API

# ---------------------------------------------------------------- build
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app

# Resolve dependencies in their own layer so that a source-only change does not
# re-download the world on every build.
COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline

COPY src/ src/
RUN mvn -B -ntp package -DskipTests

# ---------------------------------------------------------------- runtime
FROM eclipse-temurin:17-jre
WORKDIR /app

COPY --from=build /app/target/*.jar app.jar

# Run as a non-root user, and give group 0 the same rights as the owner.
# OpenShift assigns an arbitrary UID in group 0 rather than the UID declared here,
# so group-0 permissions are what actually make the image runnable there. The
# restricted PodSecurity profile likewise rejects a container that runs as root.
RUN chgrp -R 0 /app && chmod -R g=u /app
USER 1001

# The business date is derived from the JVM default zone, so it must not depend on
# the host's timezone.
ENV TZ=UTC
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70"

EXPOSE 9090

# Shell form, deliberately: the exec form never expands $JAVA_OPTS, so the memory
# settings documented in DEPLOY.md and render.yaml were silently ignored. exec
# keeps the JVM as PID 1 so it still receives SIGTERM for graceful shutdown.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -Duser.timezone=UTC -jar app.jar"]
