FROM --platform=linux/amd64 amazoncorretto:17
WORKDIR /application
COPY ./build/libs/ /application/

RUN curl https://dtdg.co/latest-java-tracer -o dd-java-agent.jar
ENV OPTION=-javaagent:/application/dd-java-agent.jar

ENV JAR_TARGET "/application/sample-application.jar"
ENTRYPOINT ["sh","-c","java -jar ${JAR_TARGET} ${OPTION}"]