FROM eclipse-temurin:17-jre

ARG JAR_FILE
ARG SERVICE_PORT=8080

WORKDIR /app
RUN groupadd --gid 10001 app \
    && useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin app

COPY ${JAR_FILE} /app/app.jar
RUN chown 10001:10001 /app/app.jar

USER 10001:10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"
EXPOSE ${SERVICE_PORT}

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
