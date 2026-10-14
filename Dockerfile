FROM maven:3.9.11-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
COPY src src
COPY scripts scripts
RUN mvn -B -ntp verify
FROM eclipse-temurin:17-jre
WORKDIR /app
RUN groupadd --system plastiflow && useradd --system --gid plastiflow plastiflow
COPY --from=build /build/target/plastiflow.jar app.jar
COPY scripts/healthcheck.sh /app/healthcheck.sh
USER plastiflow
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 CMD ["bash", "/app/healthcheck.sh"]
ENTRYPOINT ["java", "-jar", "app.jar"]
