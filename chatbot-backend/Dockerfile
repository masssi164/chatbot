# Production stage - use JRE for smaller image
FROM eclipse-temurin:17-jre

WORKDIR /app

# Install curl for health checks and create non-root user (Ubuntu/Debian syntax)
RUN apt-get update && apt-get install -y --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/* && \
    groupadd -r spring && useradd -r -g spring spring

# Copy the pre-built jar from the build directory
# The JAR is built by Gradle on the host before Docker build
COPY build/libs/*.jar app.jar

# Change ownership to spring user
RUN chown spring:spring app.jar

USER spring:spring

# Expose port
EXPOSE 8080

# Health check (using curl instead of wget for Ubuntu base image)
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1

# Run the application
ENTRYPOINT ["java", "-jar", "app.jar"]
