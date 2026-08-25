# Production-oriented runtime image (multi-stage).
#
# Build the JAR on the host or in CI, then build the image:
#   ./mvnw package -DskipTests
#   docker build -t cartforge-api:local .
#
# For unrestricted CI networks, use docker/Dockerfile.ci to compile inside Docker.
FROM busybox:1.37.0-musl AS artifact

WORKDIR /workspace
COPY target/ecommerce-api-*.jar app.jar
RUN test -s app.jar

# --- Runtime stage: JRE-only, non-root, no build tooling or secrets. ---
FROM eclipse-temurin:21-jre-jammy AS runtime

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 app \
    && useradd --uid 10001 --gid app --home-dir /app --shell /usr/sbin/nologin app \
    && mkdir -p /app /tmp/app \
    && chown -R app:app /app /tmp/app

WORKDIR /app

COPY --from=artifact --chown=app:app /workspace/app.jar /app/app.jar
COPY --chown=app:app docker/entrypoint.sh /app/entrypoint.sh

RUN chmod 0555 /app/entrypoint.sh /app/app.jar

USER app:app

ENV SERVER_PORT=8080 \
    JAVA_OPTS="" \
    TMPDIR=/tmp/app

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
  CMD curl -fsS "http://127.0.0.1:${SERVER_PORT}/actuator/health/liveness" >/dev/null || exit 1

STOPSIGNAL SIGTERM

ENTRYPOINT ["/app/entrypoint.sh"]
