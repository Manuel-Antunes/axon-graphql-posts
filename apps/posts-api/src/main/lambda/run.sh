#!/bin/sh
set -eu

PORT="${AWS_LWA_PORT:-8080}"

if [ -x /var/task/application ]; then
    exec /var/task/application \
        -Dquarkus.http.host=0.0.0.0 \
        -Dquarkus.http.port="$PORT"
fi

exec java \
    -Dquarkus.http.host=0.0.0.0 \
    -Dquarkus.http.port="$PORT" \
    -XX:+UseSerialGC \
    -Djava.security.egd=file:/dev/urandom \
    -jar /var/task/quarkus-app/quarkus-run.jar
