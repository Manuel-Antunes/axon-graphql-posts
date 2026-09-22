FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app
COPY target/quarkus-app/lib/ lib/
COPY target/quarkus-app/app/ app/
COPY target/quarkus-app/quarkus/ quarkus/
COPY target/quarkus-app/quarkus-run.jar quarkus-run.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/quarkus-run.jar"]
