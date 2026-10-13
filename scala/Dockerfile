# ---------- Etapa 1: compilar, probar y empaquetar (JDK + sbt) ----------
FROM eclipse-temurin:21-jdk AS build

ARG SBT_VERSION=1.10.7
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl ca-certificates \
 && rm -rf /var/lib/apt/lists/* \
 && curl -fsSL "https://github.com/sbt/sbt/releases/download/v${SBT_VERSION}/sbt-${SBT_VERSION}.tgz" | tar xz -C /opt
ENV PATH="/opt/sbt/bin:${PATH}" \
    LC_ALL=C.UTF-8

WORKDIR /app

# Capa de dependencias: solo se invalida si cambian build.sbt o project/
COPY build.sbt ./
COPY project ./project
RUN sbt -batch update

# Código fuente: las pruebas deben pasar para que la imagen se construya
COPY src ./src
RUN sbt -batch test assembly

# ---------- Etapa 2: imagen final mínima (solo JRE + fat-jar) ----------
FROM eclipse-temurin:21-jre

# curl: lo usa el HEALTHCHECK de docker-compose
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --create-home gamestats
WORKDIR /data
COPY --from=build /app/target/scala-3.3.4/gamestats-analytics.jar /opt/gamestats/app.jar
USER gamestats

EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=15s --retries=5 \
  CMD curl -fsS http://localhost:8080/health || exit 1

# Por defecto corre como servicio HTTP para el módulo Go (POST /analyze, GET /health en :8080).
#   docker run --rm -p 8080:8080 gamestats
# También sirve como herramienta de línea de comandos (se reemplaza el CMD):
#   docker run --rm -v "$PWD:/data" gamestats analyze events.ndjson --out results.json
#   docker run --rm -v "$PWD:/data" gamestats simulate --edge-cases --out events.ndjson
ENTRYPOINT ["java", "-jar", "/opt/gamestats/app.jar"]
CMD ["serve", "--host", "0.0.0.0", "--port", "8080"]
