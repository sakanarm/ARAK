# Two stages: a JDK to build the shaded jar, a JRE to run it.
#
# Java 21 in both, matching OpenMetadata 2.0.1 and the release set in the root
# pom. Building inside the image keeps the published artifact reproducible from
# a checkout alone, with no local toolchain involved.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY backend/ backend/
COPY spec/ spec/
RUN ./mvnw -B -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/backend/dac-service/target/dac-service.jar /app/dac-service.jar
COPY conf/ /app/conf/
EXPOSE 8080 8081
# Dropwizard's own health endpoint, not a TCP check: the process can be up while
# the app database is unreachable, and that instance must not take traffic.
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s \
  CMD wget -qO- http://localhost:8081/healthcheck || exit 1
ENTRYPOINT ["java", "-jar", "/app/dac-service.jar", "server", "/app/conf/dac.yml"]
