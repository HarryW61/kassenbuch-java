# Kassenbuch - Abhaengigkeit frei, nur JDK.
#
#   docker build -t kassenbuch .
#   docker run -p 8090:8090 --user $(id -u):$(id -g) -v "$PWD/data:/data" -v "$PWD/out:/out" kassenbuch
#
# /data holds the live KABU.DBF, JABU.DBF and STAMM.MEM.
# /out holds what the program generates (year-end CSV, tar.gz backups) under /out/bak;
# reports are streamed as downloads and never written to disk.
# docker-compose.yml additionally sets container_name and user: "${UID}:${GID}", so
# files created in mounted host directories belong to the invoking user, not to root.
# Build stage: compile with the JDK (no Maven/Gradle, no network fetches).
# Runtime stage: only the compiled classes plus the web assets.

FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY src/main/java src/main/java
RUN javac --release 17 -d out $(find src/main/java -name '*.java')

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/out out
COPY web web
COPY LICENSE NOTICE.txt README.md ./

# Run as a non-root user. Without this the container writes as uid 0, so the backup
# directory, the tar.gz archives and the year-end copy KABU.YY land on the host volume
# owned by root - unreadable and undeletable for the normal user without sudo.
# The base image already ships a regular user "ubuntu" with uid/gid 1000; reusing it
# avoids clashing with that id. docker run --user $(id -u):$(id -g) overrides the id at
# run time, which is what makes host-mounted directories work cleanly in both directions.
RUN mkdir -p /data /out && chown -R 1000:1000 /data /out
USER 1000:1000

# Bookings live in a volume so they survive a container restart.
ENV KB_DATA=/data KB_OUT=/out KB_PORT=8090 KB_WEB=/app/web
VOLUME ["/data"]
VOLUME ["/out"]
EXPOSE 8090

ENTRYPOINT ["java", "-cp", "out", "kb.WebServer"]
