FROM eclipse-temurin:8-jre-ubi9-minimal
COPY /target/bimplatform.jar /usr/local/lib/bimplatform.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","/usr/local/lib/bimplatform.jar","--spring.profiles.active=prod,minio"]
