FROM gradle:9.5.0-jdk17 AS build
WORKDIR /workspace
ENV MONETIZEI_SERVER_ONLY=true
COPY . .
RUN gradle :server:installDist --no-daemon --stacktrace

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/server/build/install/server/ /app/
ENV MONETIZEI_DB_PATH=/data/monetizei.db
RUN mkdir -p /data
EXPOSE 8080
CMD ["bin/server"]
