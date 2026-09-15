# ---- build ---------------------------------------------------------------
FROM eclipse-temurin:21-jdk AS build
ARG SBT_VERSION=1.10.5
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
 && curl -fsSL "https://github.com/sbt/sbt/releases/download/v${SBT_VERSION}/sbt-${SBT_VERSION}.tgz" | tar xz -C /opt \
 && ln -s /opt/sbt/bin/sbt /usr/local/bin/sbt
WORKDIR /src
# Warm the dependency cache on a layer that only changes when the build definition changes.
COPY build.sbt .
COPY project/build.properties project/plugins.sbt project/
RUN sbt -batch update
COPY . .
RUN sbt -batch "app/stage"

# ---- runtime -------------------------------------------------------------
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 lattice
WORKDIR /opt/lattice
COPY --from=build /src/app/target/universal/stage/ .
USER lattice
ENV PORT=8080 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseZGC -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s CMD curl -fsS http://localhost:8080/health || exit 1
ENTRYPOINT ["bin/lattice"]
