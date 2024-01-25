# Stage 1: Build
FROM maven:3.9-eclipse-temurin-21-jammy AS build

WORKDIR /app
COPY auditor /app

RUN mvn clean install

# Stage 2: Deployment
FROM eclipse-temurin:21-jammy

WORKDIR /app
COPY --from=build /app/target/*-SNAPSHOT.jar /app/app.jar

ENTRYPOINT java -jar /app/app.jar