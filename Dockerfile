# syntax=docker/dockerfile:1
# ---- Build stage: compile the React SPA (via frontend-maven-plugin) + the JAR ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Warm the dependency cache: copy manifests first so `docker build` reuses the
# layer unless pom.xml / the npm lockfile actually change.
COPY pom.xml ./
COPY frontend/package.json frontend/package-lock.json ./frontend/
RUN mvn -B -q dependency:go-offline || true

COPY . .
# Builds the Vite SPA into src/main/resources/static and packages the fat JAR.
RUN mvn -B -DskipTests clean package

# ---- Runtime stage: slim JRE, non-root ----
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# Run as an unprivileged user.
RUN useradd --system --uid 10001 --create-home appuser
COPY --from=build /app/target/realtime-codeshare-*.jar /app/app.jar
USER 10001

EXPOSE 8080
ENV JAVA_OPTS=""
# Honor SPRING_PROFILES_ACTIVE / env-based config at runtime.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
