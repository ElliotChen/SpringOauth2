FROM openjdk:8-jdk-alpine
VOLUME /tmp
EXPOSE 8080

ARG JAR_FILE
ADD ${JAR_FILE} 02resourceserver.jar
ENTRYPOINT ["java", "-jar", "02resourceserver.jar"]