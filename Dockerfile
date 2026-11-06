# Kassenbuch - Abhaengigkeit frei, nur JDK.
#
#   docker build -t kassenbuch .
#   docker run -p 8090:8090 --user $(id -u):$(id -g) -v "$PWD/data:/data" -v "$PWD/out:/out" kassenbuch
#
# /data holds the live KABU.DBF, JABU.DBF and STAMM.MEM - see docker/README.md for how a
#   fresh clone gets them into the volume.
# /out holds what the program generates (year-end CSV, tar.gz backups) under /out/bak;
# reports are streamed as downloads and never written to disk.
# docker-compose.yml additionally sets container_name and starts the container as root for
# the entrypoint's first step only, so files created in mounted host directories belong to
# the invoking user, not to root.
# Build stage: compile with the JDK (no Maven/Gradle, no network fetches).
# Runtime stage: only the compiled classes plus the web assets.

FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY src/main/java src/main/java
# The executable bit is deliberately set here rather than left to COPY. Git does track file
# modes, but a working tree that was moved by cp -r, rsync without -p, a Windows filesystem or
# an archive loses it - and then the container dies on "exec: kb-entrypoint: permission denied"
# before anything of ours runs. chmod makes the start independent of how the files arrived.
COPY docker/entrypoint.sh /usr/local/bin/kb-entrypoint
RUN chmod 0755 /usr/local/bin/kb-entrypoint
RUN javac --release 17 -d out $(find src/main/java -name '*.java')

# Seed stage: the three files a clone ships. COPY of an empty directory produces nothing,
# so they are copied one by one into their own stage and merged into /seed at the end.
FROM eclipse-temurin:17-jre AS seed
WORKDIR /seed
COPY data/KABU.DBF KABU.DBF
COPY data/JABU.DBF JABU.DBF
COPY data/STAMM.MEM STAMM.MEM

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/out out
COPY web web
COPY LICENSE NOTICE.txt README.md ./

# /data starts with the files a clone ships: an empty book and placeholder master data.
# Docker copies image directory CONTENT into a named volume on its first use, so this is
# what makes "docker compose up" work straight after a clone - without it the volume is
# created empty and the server exits on "KABU.DBF fehlt in /data". The entrypoint still
# fills an existing but empty volume, which covers a volume that was created before this
# existed or after somebody deleted the files.
COPY --from=seed /seed /seed

# Run as a non-root user. Without this the container writes as uid 0, so the backup
# directory, the tar.gz archives and the year-end copy KABU.YY land on the host volume
# owned by root - unreadable and undeletable for the normal user without sudo.
# The base image already ships a regular user "ubuntu" with uid/gid 1000; reusing it
# avoids clashing with that id. docker run --user $(id -u):$(id -g) overrides the id at
# run time, which is what makes host-mounted directories work cleanly in both directions.
# The seed stays under /seed as well, so the entrypoint can fill a volume that arrived
# empty (created before this existed, or emptied by hand). It holds only the files a clone
# ships - an empty book and placeholders - never anything of yours.
RUN mkdir -p /data /out && cp /seed/* /data/ && chown -R 1000:1000 /data /out

# Ownership of the volume roots cannot be fixed by the image alone: Docker creates a named
# volume as root the first time it is used, and copies the image's directory mode (0755)
# into it - not its ownership. So the chown above only helps an anonymous volume that was
# never mounted before, and a fresh "docker compose up" still leaves /data and /out owned
# by root while the app runs as uid 1000. The backup then fails on AccessDeniedException.
# kb-entrypoint.sh closes that gap: it starts once as root, chowns the two directories to
# whatever uid the app will run as, drops the CAP_CHOWN capability and re-execs itself as
# that uid. A second start finds everything already in place and does nothing.
COPY --from=build /usr/local/bin/kb-entrypoint /usr/local/bin/kb-entrypoint
RUN chmod 0755 /usr/local/bin/kb-entrypoint

USER 1000:1000

# Bookings live in a volume so they survive a container restart.
ENV KB_DATA=/data KB_OUT=/out KB_PORT=8090 KB_WEB=/app/web
VOLUME ["/data"]
VOLUME ["/out"]
EXPOSE 8090

ENTRYPOINT ["/usr/local/bin/kb-entrypoint"]
CMD ["java", "-cp", "out", "kb.WebServer"]
