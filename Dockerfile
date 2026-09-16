FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src src
COPY authorization-sdk authorization-sdk
RUN cd authorization-sdk && sha256sum -c SOURCE_SHA256SUMS && timeout --signal=TERM --kill-after=30s 10m mvn -B -ntp clean install
RUN timeout --signal=TERM --kill-after=30s 15m mvn -B -ntp -DskipTests package

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10003 --no-create-home onboarding
WORKDIR /app
COPY --from=build /build/target/source-onboarding-*.jar application.jar
USER 10003:10003
ENTRYPOINT ["java","-jar","/app/application.jar"]
