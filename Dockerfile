ARG MAVEN_IMAGE=docker.m.daocloud.io/library/maven:3.9.9-eclipse-temurin-17
ARG RUNTIME_IMAGE=docker.m.daocloud.io/library/eclipse-temurin:17-jre
FROM ${MAVEN_IMAGE} AS build

WORKDIR /workspace

# 先复制各模块 POM，源码变化时可继续复用 Maven 依赖缓存。
COPY pom.xml ./
COPY hm-common/pom.xml hm-common/pom.xml
COPY hm-api/pom.xml hm-api/pom.xml
COPY hm-gateway/pom.xml hm-gateway/pom.xml
COPY hm-user-service/pom.xml hm-user-service/pom.xml
COPY hm-shop-service/pom.xml hm-shop-service/pom.xml
COPY hm-content-service/pom.xml hm-content-service/pom.xml
COPY hm-trade-service/pom.xml hm-trade-service/pom.xml
COPY hm-ai-service/pom.xml hm-ai-service/pom.xml
COPY deploy/maven/settings.xml /root/.m2/settings.xml
RUN mvn -B -ntp -DskipTests dependency:go-offline

COPY . .
ARG MODULE
RUN test -n "${MODULE}" && mvn -B -ntp -pl "${MODULE}" -am -DskipTests package

FROM ${RUNTIME_IMAGE}

WORKDIR /app
ARG MODULE
COPY --from=build /workspace/${MODULE}/target/${MODULE}-*.jar /app/app.jar

ENV TZ=Asia/Shanghai
EXPOSE 8081 8082 8083 8084 8085 8719 10010
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
