#!/bin/sh
set -eu

# Container-friendly defaults. Override or extend through JAVA_OPTS.
if [ -z "${JAVA_OPTS:-}" ]; then
  JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8"
fi

# exec replaces the shell so Java becomes PID 1 and receives SIGTERM for graceful shutdown.
exec java ${JAVA_OPTS} -jar /app/app.jar
