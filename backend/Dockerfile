FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./pom.xml
COPY edsp-common ./edsp-common
COPY edsp-gateway ./edsp-gateway
COPY edsp-auth ./edsp-auth
COPY edsp-core ./edsp-core
COPY edsp-alert ./edsp-alert
COPY edsp-report ./edsp-report
ARG SERVICE_MODULE
RUN mvn -pl ${SERVICE_MODULE} -am -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
ARG SERVICE_MODULE
COPY --from=build /workspace/${SERVICE_MODULE}/target/*.jar /app/service.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/service.jar"]
