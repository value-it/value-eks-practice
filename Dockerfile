FROM --platform=linux/amd64 amazoncorretto:17
#VOLUME /tmp

WORKDIR /build
COPY . ./

RUN ./gradlew sample-application:build

RUN mkdir /application
RUN cp sample-application/build/libs/sample-application.jar /application/

EXPOSE 38080

ENV JAR_TARGET "/application/sample-application.jar"
ENTRYPOINT ["sh","-c","java -jar ${JAR_TARGET}"]