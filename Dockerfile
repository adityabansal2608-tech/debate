
FROM eclipse-temurin:17-jdk

WORKDIR /app

COPY debatecoach.java .

RUN javac debatecoach.java

EXPOSE 8080

CMD ["java", "debatecoach"]