FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src src
COPY authorization-sdk authorization-sdk
RUN cd authorization-sdk && sha256sum -c SOURCE_SHA256SUMS && timeout --signal=TERM --kill-after=30s 10m mvn -B -ntp clean install
RUN timeout --signal=TERM --kill-after=30s 15m mvn -B -ntp -DskipTests package

FROM eclipse-temurin:21-jre@sha256:0b99b3376fbb50dbf9c0af8535fdd5b8eb0d3fdfc8b1e4967f383b6919f6901a
# USN-8996-1: patch APT before using the runtime image; retries=0 mitigates
# CVE-2026-109518 during the update itself. No live host packages are changed.
RUN apt-get -o Acquire::Retries=0 update \
 && apt-get -o Acquire::Retries=0 install -y --no-install-recommends --only-upgrade apt=3.2.1ubuntu0.1 libapt-pkg7.0=3.2.1ubuntu0.1 \
 && rm -rf /var/lib/apt/lists/*
# Java is the sole entrypoint; the base-image Pebble Go supervisor is unused.
RUN rm -f /usr/bin/pebble
RUN useradd --system --uid 10003 --no-create-home onboarding
WORKDIR /app
COPY --from=build /build/target/source-onboarding-*.jar application.jar
USER 10003:10003
ENTRYPOINT ["java","-jar","/app/application.jar"]
