# GameStats — Estado del Proyecto e Integración (Daniel y Santiago)

**Para:** Santiago (@SpedrazaDev)  
**De:** Daniel (@CespGonzaDev)  
**Repositorio Oficial Unificado:** [https://github.com/CespGonzaDev/GameStats.git](https://github.com/CespGonzaDev/GameStats.git)  
**Fecha:** 12 de Octubre de 2026  

---

## 1. Resumen Ejecutivo: ¿Dónde estamos?

El proyecto ya cuenta con **ambos módulos (Go y Scala) completamente implementados, integrados, probados y orquestados en Docker**.

Unificamos el trabajo en el repositorio oficial **GameStats** para mantener una estructura profesional acorde a lo solicitado en el enunciado (§10). Ya tienes la invitación para colaborar con permisos de administrador en el repo.

A continuación se detalla qué hay en el repositorio, qué funciona, los ajustes técnicos realizados y qué resta para la entrega final.

---

## 2. Lo que ya está construido e implementado

### A. Módulo Go (Daniel — Ingestión, Validación y Concurrencia)
Siguiendo exactamente las pautas arquitectónicas acordadas y tu guía técnica:
1. **Validación Agnóstica del Envelope (`internal/event`):**
   * Valida estrictamente los 9 campos obligatorios del sobre.
   * Exige UUID v4 en `eventId`, timestamp ISO-8601 (`RFC3339Nano`) y que `data` sea un objeto JSON `{...}`.
   * Rechaza campos adicionales desconocidos en el sobre (`DisallowUnknownFields`).
   * **100% extensible:** No valida `gameId` ni `action` contra listas cerradas, permitiendo que cualquier juego nuevo (`chess`, cartas, etc.) ingrese sin tocar código en Go.
2. **Pipeline Concurrente con Goroutines y Canales (`internal/pipeline`):**
   * **Pool de $N$ Validadores:** Procesan en paralelo el flujo de entrada; registran eventos corruptos en un buffer circular FIFO y loguean con `log/slog`.
   * **Despachador Central (1 goroutine):** Dueño exclusivo del mapa de canales por partida (`map[matchId]chan Event`), eliminando riesgos de condiciones de carrera (*race conditions*). Incluye un recolector periódico que libera de memoria partidas que superen `RETENTION` (10 min de inactividad).
   * **Goroutine por Partida (`runMatch`):** Maneja el estado aislado de cada partida sin necesidad de cerrojos (`Mutex`). Deduplica por `eventId` (semántica *at-least-once*).
   * **Manejo de Desorden y Eventos Tardíos:** Espera una ventana de gracia (`FLUSH_GRACE = 2s`) tras recibir `MATCH_FINISHED` antes de emitir el lote a Scala. Si la partida queda abandonada sin fin, despacha tras `IDLE_TIMEOUT = 30s` (estado `IN_PROGRESS`). Si llega un evento tardío tras un despacho previo, reemite la partida completa actualizada (aprovechando que Scala es idempotente).
   * **Apagado Ordenado (*Graceful Shutdown*):** Captura señales del SO (`SIGINT`, `SIGTERM`), drena los canales en cascada, vacía las partidas pendientes y despacha a Scala antes de salir.
3. **Cliente HTTP hacia Scala (`internal/scala`):**
   * Despacha lotes con los bytes originales (`Raw`) de cada evento en `POST /analyze`.
   * Implementa reintentos con **backoff exponencial** para fallos de red o errores HTTP `5xx`.
   * Falla de inmediato y sin reintentos ante errores `400`, `413` o `422`.
4. **Almacén Thread-Safe y Servidor REST (`internal/store` e `internal/api`):**
   * Almacén en memoria (`sync.RWMutex`) para almacenar los resultados analíticos devueltos por Scala y exponerlos vía API.
   * Buffer circular para consultar los últimos $N$ eventos descartados con su motivo de fallo.
   * Servidor HTTP escuchando en `:8081` con las rutas:
     * `POST /events`: Ingesta de eventos (NDJSON, array o wrapper). Aplica contrapresión (`503`) si la cola se llena.
     * `GET /matches`: Lista resumida de partidas analizadas (ID, juego, estado, patrones detectados).
     * `GET /matches/{matchId}`: JSON analítico completo devuelto por Scala.
     * `GET /invalid`: Lista de eventos descartados y su motivo (`?limit=50`).
     * `GET /stats`: Métricas operativas (uptime, eventos recibidos, válidos, lotes, errores).
     * `GET /health`: Liveness probe.

### B. Módulo Scala (Santiago — Análisis Funcional y Simulación)
* Incorporado en la carpeta `scala/` con su jerarquía completa de paquetes (`gamestats.model`, `gamestats.analytics`, `gamestats.parsing`, `gamestats.server`, `gamestats.sim`).
* Reglas de patrones complejos implementadas funcionalmente:
  * Remontada Épica en Head Football (`COMEBACK`).
  * Racha Crítica en Battleship (`DEADLY_STREAK`).
  * Ventana de conversión en Agar.io y eficiencia de movimiento en Snake.
* Suite de 75 pruebas unitarias funcionales pasando al 100%.

### C. Módulo DevOps y CI/CD (Daniel con soporte de Santiago)
* **`go/Dockerfile`:** Compilación multi-stage con binario estático ligero en Alpine.
* **`scala/Dockerfile`:** Compilación con sbt, validación de tests y empaquetado del fat-jar con Temurin JRE 21.
* **`docker-compose.yml`:** Orquesta ambos servicios en red compartida:
  * `scala`: Puerto `8080`, con healthcheck en `/health`.
  * `go`: Puerto `8081`, con `depends_on: condition: service_healthy`.
* **`.github/workflows/ci.yml`:** Pipeline de GitHub Actions que corre en cada push y PR:
  1. Go: `go vet`, `go test -race ./...`, build.
  2. Scala: `sbt test`, `sbt assembly`.
  3. Docker: Build de ambas imágenes.

---

## 3. ¿Qué funciona actualmente? (Verificado)

1. **Pruebas unitarias de Go:** 100% pasando (`gamestats/internal/event`, `store`, `scala`, `pipeline`, `ingest`, `api`).
2. **Pruebas unitarias de Scala:** 100% pasando (75 de 75 tests ejecutados en el build de Docker).
3. **Construcción de Contenedores:** `docker compose build` construye exitosamente ambas imágenes (`gamestats-go:latest` y `gamestats-scala:latest`).
4. **Flujo de Integración End-to-End:**
   * Go recibe los eventos de `examples/edge-cases.ndjson` o `examples/sample-matches.ndjson`.
   * Go valida, agrupa y despacha por HTTP a Scala.
   * Scala analiza con pureza funcional y responde con las métricas y patrones.
   * Go almacena el resultado y lo expone inmediatamente en `http://localhost:8081/matches`.

---

## 4. Ajustes menores realizados durante la integración

Para que ambos módulos ensamblaran limpiamente en Docker y en entornos multiplataforma (Windows y Linux), se aplicaron dos correcciones menores:

1. **Compatibilidad CRLF en `EventParserSpec.scala`:**
   * En sistemas Windows con Git configurado con CRLF, la constante `valid` en el test tenía `replace("\n", "")` tras un `stripMargin`, dejando un carácter `\r` residual dentro de la cadena JSON. Se ajustó a `.replace("\r", "").replace("\n", "")` para que los 75 tests pasen en cualquier sistema operativo.
2. **Versión en `go.mod`:**
   * Se fijó `go 1.23` en `go.mod` para garantizar que la imagen base `golang:1.24-alpine` en Docker y GitHub Actions compile sin conflictos de toolchain.

---

## 5. ¿Qué falta para la entrega? (Pendientes de equipo)

Para dar por cerrado el proyecto con nota máxima, nos resta coordinar lo siguiente:

| Tarea | Responsables | Detalle |
|---|---|---|
| **1. Documento Académico Final** | Daniel y Santiago | Redactar el informe formal para el Tec Digital según §11 y §13 del enunciado (Justificación teórica de Go vs Scala, especificación de eventos, catálogo de métricas y manual de usuario). |
| **2. Confirmar Fecha Límite** | Equipo con Profesor | El enunciado dice *"domingo 18 de Setiembre"*, pero ese día cayó viernes (el domingo 18 cae en octubre). Debemos validar la fecha real en el Tec Digital. |
| **3. Ensayo de Demostración (Demo)** | Daniel y Santiago | Definir el guion de 5 a 10 minutos para la defensa en vivo: levantar con `docker compose up`, emitir una partida desde el simulador y mostrar las métricas en JSON. |

---

## 6. Comandos rápidos para probar localmente

```powershell
# 1. Clonar el repositorio oficial
git clone https://github.com/CespGonzaDev/GameStats.git
cd GameStats

# 2. Levantar la plataforma completa en Docker
docker compose up --build

# 3. En otra terminal, enviar partidas de prueba (casos borde)
curl.exe -X POST --data-binary "@examples\edge-cases.ndjson" http://localhost:8081/events

# 4. Consultar resultados analíticos
curl.exe http://localhost:8081/matches
curl.exe http://localhost:8081/stats
```
