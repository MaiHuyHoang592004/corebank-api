# Multi-stage Dockerfile for CoreBank API
# Stage 1: Build
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src/ src/
# mvnw and .mvn are needed for Maven wrapper builds
COPY mvnw .
COPY mvnw.cmd .
COPY .mvn/ .mvn/
RUN mvn package -DskipTests -q

# Stage 2: Runtime
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
EXPOSE 9090
ENTRYPOINT ["java", "-jar", "app.jar"]
