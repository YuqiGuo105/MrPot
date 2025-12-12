# =========
# 1) Build stage
# =========
FROM maven:3.9-eclipse-temurin-21 AS build


WORKDIR /app

# First copy pom.xml and download dependencies (cache-friendly)
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

# Now copy sources and build
COPY src ./src

# Skip tests for faster Docker builds; remove -DskipTests if you prefer
RUN mvn -B -DskipTests package

# =========
# 2) Runtime stage
# =========
FROM eclipse-temurin:21-jre-alpine

# Create non-root user for security
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring

WORKDIR /app

# Copy fat jar from build stage
COPY --from=build /app/target/*.jar app.jar

# Expose default Spring Boot port
EXPOSE 8080

# Active profile is "dev" by default; override at runtime if needed
ENV SPRING_PROFILES_ACTIVE=dev

# You can also pass env vars via docker run / docker-compose (.env file)
# Example (not required here):
# ENV SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/mrpot

# Use JAVA_OPTS for tuning if you want to pass extra JVM args
ENV JAVA_OPTS=""

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
