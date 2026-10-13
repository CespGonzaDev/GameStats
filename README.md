# GameStats — Plataforma Multilenguaje de Ingestión y Análisis de Videojuegos

Plataforma distribuida desarrollada para el curso de **Lenguajes de Programación** (II Semestre 2026). Integra dos paradigmas de programación complementarios para resolver la ingestión de alta concurrencia y el análisis estadístico en tiempo real de partidas de videojuegos.

* **Daniel (Módulo 2 e Infraestructura):** Ingestión concurrente e imperativa en **Go** (goroutines, channels, validación agnóstica del sobre, buffers thread-safe, despacho asíncrono y API REST).
* **Santiago (Módulo 1 y 3):** Generación de datos/simulación y motor analítico funcional en **Scala** (inmutabilidad, colecciones funcionales, pattern matching y detección de secuencias).

---

## 📁 Estructura del Repositorio

```
GameStats/
├── go/                     # Servicio de Ingestión, Validación y Concurrencia (Go)
│   ├── cmd/gamestats/      # Punto de entrada principal y manejo de señales del SO
│   ├── internal/event/     # Validación estricta y agnóstica del Envelope
│   ├── internal/pipeline/  # Pipeline concurrente (despachador, actors por partida, workers)
│   ├── internal/scala/     # Cliente HTTP con reintentos exponenciales hacia Scala
│   ├── internal/store/     # Almacén de partidas en memoria y buffer circular de inválidos
│   ├── internal/ingest/    # Procesador de flujos NDJSON, JSON arrays y wrappers
│   ├── internal/api/       # Servidor HTTP REST (POST /events, GET /matches, etc.)
│   └── Dockerfile          # Multi-stage build para Go
├── scala/                  # Motor Analítico Funcional y Simulador (Scala / sbt)
│   ├── src/main/scala/     # Case classes, parsers, analizadores funcionales y servidor
│   ├── src/test/scala/     # Suite de 75 pruebas unitarias funcionales
│   └── Dockerfile          # Imagen Docker con sbt y JVM
├── examples/               # Archivos de eventos NDJSON de referencia y casos borde
│   ├── edge-cases.ndjson   # Casos para verificar remontadas, rachas y desorden
│   ├── edge-cases.results.json # Salida esperada de los casos borde
│   └── sample-matches.ndjson   # Partidas sintéticas para pruebas de carga
├── docs/                   # Especificaciones y esquemas
│   ├── README.md           # Documentación técnica detallada del Módulo Go
│   ├── GUIA_MODULO_GO.md   # Guía de integración y decisiones del enlace
│   └── results.schema.json # JSON Schema de la salida analítica
├── docker-compose.yml      # Orquestación de ambos servicios
└── .github/workflows/      # CI/CD automatizado con GitHub Actions
```

---

## 🚀 Despliegue con Docker Compose (Recomendado)

Levanta la plataforma completa (ambos servicios intercomunicados por red interna):

```bash
docker compose up --build
```

* **Servicio Go:** Escuchando en `http://localhost:8081`
* **Servicio Scala:** Escuchando en `http://localhost:8080` (con healthcheck activo)

---

## 🌐 Endpoints de la API (Servicio Go: `:8081`)

| Método | Ruta | Descripción |
|---|---|---|
| `POST` | `/events` | Ingesta de eventos (acepta NDJSON, array JSON o wrapper). Devuelve `202 Accepted`. |
| `GET` | `/matches` | Lista resumida de partidas analizadas con estado y patrones detectados. |
| `GET` | `/matches/{matchId}` | JSON analítico completo devuelto por Scala para esa partida. |
| `GET` | `/invalid` | Últimos eventos corruptos rechazados con motivo (`?limit=50`). |
| `GET` | `/stats` | Métricas operativas (uptime, eventos recibidos, válidos, lotes, errores). |
| `GET` | `/health` | Verificación de salud del servicio Go. |

---

## 🧪 Pruebas y Desarrollo Local

### Módulo Go
```bash
cd go
# Ejecutar suite completa de pruebas unitarias
go test -v ./...

# Arrancar servicio en local apuntando a Scala
go run ./cmd/gamestats -scala http://localhost:8080 -addr :8081
```

### Módulo Scala
```bash
cd scala
# Ejecutar suite de pruebas unitarias
sbt test

# Compilar jar ensamblado
sbt assembly
```

---

## ⚙️ Integración Continua (CI/CD)

El pipeline configurado en `.github/workflows/ci.yml` ejecuta en cada `push` y `pull request`:
1. **Job Go:** `go vet`, `go test -race` y compilación estática.
2. **Job Scala:** `sbt test` y compilación de artefacto con `assembly`.
3. **Job Docker:** Construcción de imágenes de contenedores para ambos módulos.
