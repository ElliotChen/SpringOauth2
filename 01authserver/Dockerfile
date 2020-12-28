FROM openjdk:8-jdk-alpine
VOLUME /tmp
EXPOSE 8080

ARG JAR_FILE
ADD ${JAR_FILE} 01authserver.jar
ENTRYPOINT ["java", "-jar", "01authserver.jar"]