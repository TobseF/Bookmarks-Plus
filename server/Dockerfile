# Bookmarks Plus sync server. Builds on the output of `gradlew :server:installDist`:
#   ./gradlew :server:installDist && docker build -t bookmarks-plus-server server
FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S bmp && adduser -S -G bmp -u 10001 bmp \
    && mkdir -p /data && chown bmp:bmp /data

COPY build/install/bookmarks-plus-server /opt/bookmarks-plus-server

ENV BMP_DATA=/data \
    BMP_PORT=8787 \
    BMP_HOST=0.0.0.0

VOLUME /data
EXPOSE 8787
USER bmp

ENTRYPOINT ["/opt/bookmarks-plus-server/bin/bookmarks-plus-server"]
