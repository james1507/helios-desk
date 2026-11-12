FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY src src
RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests package
FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* && useradd -r -u 10001 helios
WORKDIR /app
COPY --from=build /src/target/helios-api-0.1.0.jar app.jar
USER helios
EXPOSE 8085 9085
ENTRYPOINT ["java","-jar","app.jar"]
