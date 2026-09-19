# build stage: compile the jar with maven
FROM maven:3.9-eclipse-temurin-25 AS build

WORKDIR /app

# copy the pom.xml first so the dependency layer is cached and only re-runs when the pom changes
COPY pom.xml .
RUN mvn -B dependency:go-offline

# then the source which changes on every edit
COPY src ./src
RUN mvn -B -DskipTests package

# runtime stage: jre + python3 only
FROM eclipse-temurin:25-jre

# the service shells out to python3, so the python has to be in the image
# clean the apt lists in the same RUN, otherwise they stay in this layer
RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 \
    && rm -rf /var/lib/apt/lists/*

# never run submitted code as root
RUN useradd --create-home --uid 1001 app

WORKDIR /app
COPY --from=build /app/target/server-0.0.1-SNAPSHOT.jar /app/server.jar

USER app
EXPOSE 8080

# exec form: the jvm becomes pid 1 and receives SIGTERM from "docker stop"
ENTRYPOINT ["java", "-jar", "/app/server.jar"]
