# Kassenbuch - Abhaengigkeit frei, nur JDK.
#
#   docker build -t kassenbuch .
#   docker run -p 8090:8090 -v "$PWD/data:/data" -v "$PWD/out:/out" kassenbuch
#
# /data holds the live KABU.DBF, JABU.DBF and STAMM.MEM.
# /out holds what the program generates (year-end CSV, tar.gz backups) under /out/bak;
# reports are streamed as downloads and never written to disk.
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

# Bookings live in a volume so they survive a container restart.
ENV KB_DATA=/data KB_OUT=/out KB_PORT=8090 KB_WEB=/app/web
VOLUME ["/data"]
VOLUME ["/out"]
EXPOSE 8090

ENTRYPOINT ["java", "-cp", "out", "kb.WebServer"]
