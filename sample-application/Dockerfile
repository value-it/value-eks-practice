FROM --platform=linux/arm64 amazoncorretto:17
WORKDIR /application
COPY ./build/libs/ /application/

ENV JAR_TARGET "/application/sample-application.jar"
ENTRYPOINT ["sh","-c","java -jar ${JAR_TARGET}"]