# GameStats — Módulo de Ingestión, Validación y Concurrencia en Go

## 1. Visión General y Justificación de Paradigmas

GameStats es una plataforma desacoplada para ingestión y análisis estadístico en tiempo real de partidas de videojuegos. La solución combina dos paradigmas complementarios:

* **Go (Ingestión Concurrente e Imperativa):** Se especializa en recibir flujos masivos de eventos externos, validar la integridad del sobre universal (*Envelope*), organizar y acumular eventos por partida en memoria sin condiciones de carrera, y despachar lotes a demanda. Go sobresale por su bajo consumo de memoria, rápida inicialización, primitivas nativas de concurrencia (`goroutines` y `channels`), y control estricto de recursos.
* **Scala (Análisis Funcional Declarativo):** Se especializa en la transformación inmutable de colecciones, cálculo de métricas avanzadas, agregaciones por jugador y reconocimiento de patrones temporales (`Deadly Streak`, `Comeback`) mediante funciones de orden superior (`map`, `filter`, `foldLeft`, `groupBy`) sin efectos secundarios ni mutación de estado.

```
       FUENTES EXTERNAS (HTTP / NDJSON)
                     │
                     ▼
       ┌───────────────────────────────┐
       │   N Validadores (Goroutines)  │ ──► Registro de Inválidos (Buffer Circular)
       └───────────────────────────────┘
                     │  Eventos válidos
                     ▼
       ┌───────────────────────────────┐
       │   Despachador (1 Goroutine)   │ ──► Limpieza periódica por RETENTION
       └───────────────────────────────┘
          │          │          │
          ▼          ▼          ▼
       [Match 1]  [Match 2]  [Match N]  (Goroutines independientes por partida)
          │          │          │       (Deduplicación, FLUSH_GRACE, IDLE_TIMEOUT)
          └──────────┼──────────┘
                     │  Lotes (Batch)
                     ▼
       ┌───────────────────────────────┐
       │   M Workers HTTP a Scala      │ ──► POST /analyze (Retries con Backoff Exponencial)
       └───────────────────────────────┘
                     │  Resultados JSON
                     ▼
       ┌───────────────────────────────┐
       │  Almacén en Memoria (RWMutex) │ ◄── GET /matches, GET /matches/{id}, GET /stats
       └───────────────────────────────┘
```

---

## 2. Arquitectura de Concurrencia (Módulo 2)

El pipeline implementa el patrón idiomático de Go: *"No comuniques compartiendo memoria; comparte memoria comunicando"*.

1. **Pool de Validadores (`N` goroutines):**
   - Consume del canal de entrada `rawChan`.
   - Verifica los 9 campos obligatorios del sobre (*Envelope*), formato UUID v4, timestamp ISO 8601 (`RFC3339Nano`) y que el payload `data` sea un objeto JSON.
   - Si el evento es corrupto, se registra con `log/slog`, se incrementan métricas y se almacena en el buffer de eventos descartados.
   - Si es válido, se remite a `validChan`.
2. **Despachador Central (1 goroutine):**
   - Es el dueño exclusivo de la tabla de partidas en curso `map[string]chan event.Event`.
   - Al recibir un evento para una nueva partida, instancia un canal dedicado y lanza una goroutine `runMatch`.
   - Ejecuta un recolector periódico (`time.Ticker`) que detecta partidas que superen `RETENTION` (por defecto 10 min de inactividad) y cierra sus canales de forma segura.
3. **Goroutine por Partida (`runMatch`):**
   - Posee el estado de la partida de forma aislada (cero bloqueos de `sync.Mutex`).
   - Deduplica eventos por `eventId` (at-least-once).
   - Manejo de desorden y eventos tardíos:
     - Si recibe `MATCH_FINISHED`, arma un temporizador de gracia (`FLUSH_GRACE`, por defecto 2s) para esperar eventos retrasados antes de despachar.
     - Si la partida se abandona y nunca llega `MATCH_FINISHED`, el temporizador dispara tras `IDLE_TIMEOUT` (por defecto 30s) para que Scala analice el progreso parcial (`IN_PROGRESS`).
     - Si llegan eventos tardíos tras haber despachado, la partida reemite el lote completo actualizado; dado que Scala es idempotente, el resultado en memoria se sobreescribe limpiamente.
4. **Pool de Workers a Scala (`M` goroutines):**
   - Consume del canal `batchChan`.
   - Envía solicitudes HTTP `POST /analyze` al servicio de Scala reusando los bytes originales (`Raw`) de cada evento.
   - Maneja reintentos con backoff exponencial ante errores transitorios de red o códigos HTTP 5xx. Códigos definitivos (400, 413, 422) no son reintentados.
5. **Apagado Ordenado (*Graceful Shutdown*):**
   - Al recibir `SIGINT` o `SIGTERM`, el servidor HTTP rechaza nuevas peticiones.
   - El canal de entrada se cierra, los validadores drenan, el despachador cierra las partidas activas, cada partida emite su lote final y los workers completan el análisis antes de salir.

---

## 3. Endpoints de la API HTTP (Puerto 8081)

| Método | Ruta | Descripción | Respuesta Exitosa |
|---|---|---|---|
| `POST` | `/events` | Ingesta de eventos (acepta NDJSON, array JSON o wrapper `{"events":[...]}`). Aplica contrapresión (503) si la cola se satura. | `202 Accepted` |
| `GET` | `/matches` | Listado resumen de partidas analizadas (ID, juego, estado, patrones y conteo de eventos). | `200 OK` |
| `GET` | `/matches/{matchId}` | JSON analítico completo devuelto por Scala para esa partida. | `200 OK` (o `404 Not Found`) |
| `GET` | `/invalid` | Últimos eventos corruptos descartados con motivo de rechazo (`?limit=50`). | `200 OK` |
| `GET` | `/stats` | Métricas operativas (uptime, eventos recibidos, válidos, descartados, lotes, errores). | `200 OK` |
| `GET` | `/health` | Endpoint de salud para Docker / orquestadores. | `200 OK {"status":"ok"}` |

---

## 4. Guía de Ejecución y Pruebas

### Ejecución de Pruebas Unitarias
```bash
cd go
go test -v ./...
```

### Ejecución en Modo Servicio
```bash
cd go
# Variables de entorno opcionales: HTTP_ADDR=:8081, SCALA_URL=http://localhost:8080
go run ./cmd/gamestats
```

### Ingesta Directa de un Archivo
```bash
cd go
go run ./cmd/gamestats -file ruta/a/eventos.ndjson
```

### Despliegue con Docker Compose
```bash
docker compose up --build
```
