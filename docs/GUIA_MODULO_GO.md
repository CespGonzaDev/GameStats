# GameStats — Guía del módulo Go

Para: Daniel · De: Santiago · Estado de Scala: **listo y probado** (75 pruebas, servicio HTTP incluido)

Este documento explica **qué tienes que construir en Go y cómo se conecta con el módulo Scala que ya existe**.
Todo lo que aquí se cita (comandos, respuestas, fragmentos de código) fue probado contra el Scala real.

> **Si tienes poco tiempo**, lee la **1** (visión general), la **3** (cómo hablar con Scala) y la **5** (cuándo mandar una partida).
> El resto es referencia: 4 evento y validación · 6 concurrencia · 7 consultas · 8 configuración · 9 cómo probar ·
> 10 Docker y CI · 11 pruebas · 12 checklist · 13 decisiones pendientes.

---

## 1. En 30 segundos

```
┌──────────────────┐       ┌──────────────────────────┐       ┌──────────────────────────┐
│ SIMULADOR        │       │ MÓDULO GO (Daniel)       │       │ MÓDULO SCALA (Santiago)  │
│ (Santiago)       │       │                          │       │                          │
│                  │──────►│ recibir · validar        │──────►│ POST /analyze            │
│ genera eventos   │       │ agrupar por partida      │       │ métricas + patrones      │
│ JSON de varios   │       │ concurrencia             │◄──────│ resultado en JSON        │
│ juegos a la vez  │       │ decidir cuándo analizar  │       │ (sin guardar estado)     │
└──────────────────┘       │ guardar resultados       │       └──────────────────────────┘
                           └──────────────────────────┘                                   
                                         │
                                         ▼
                                GET /matches/{id}
```

**La regla de oro:** Go **ingiere, valida, organiza y decide cuándo analizar**. Scala **calcula**.
Go nunca calcula estadísticas (precisión, rachas, remontadas…); Scala nunca guarda estado entre llamadas.

| | Go (tú) | Scala (yo) |
|---|---|---|
| Paradigma | imperativo + concurrente | funcional puro |
| Recibe | eventos crudos de la fuente externa | lotes de eventos de **una partida completa** |
| Hace | validar, agrupar por `matchId`, goroutines/channels, estado en memoria, despachar | métricas por jugador y partida, métricas cruzadas, patrones, JSON de resultados |
| Guarda estado | sí (partidas en curso, resultados) | **no** (cada llamada es independiente) |

---

## 2. Qué te toca (según el reparto y el enunciado, §6.1 y §10)

| # | Entregable | Enunciado |
|---|---|---|
| 1 | Recibir eventos de una fuente externa (HTTP y/o archivo) | §6.1 |
| 2 | Validar el Envelope; loguear/rechazar los inválidos | §6.1 |
| 3 | Agrupar por `matchId` y seguir el ciclo de vida (inicio/fin) de cada partida | §6.1 |
| 4 | **Concurrencia real** con goroutines y channels (varias partidas/juegos en paralelo) | §6.1 |
| 5 | Despachar cada partida a Scala (`POST /analyze`) | §6.1, §7 |
| 6 | Guardar los resultados y permitir consultarlos | §9 punto 10 |
| 7 | Dockerfile multi-stage, `docker-compose.yml`, CI/CD (GitHub Actions), deployment | §10 |

### Orden de trabajo sugerido

1. `Validate` del Envelope + pruebas (sección 4). Es lo más sencillo y todo depende de eso.
2. Entrada: lector de archivo NDJSON y endpoint `POST /events`.
3. Despachador y una goroutine por partida (secciones 5 y 6).
4. Cliente de Scala (sección 3) y almacén de resultados.
5. API de consulta (sección 7).
6. Docker, compose y CI (sección 10).

Puedes empezar **ya**: Scala está listo y hay datos de ejemplo (sección 9).

---

## 3. El enlace Go → Scala (HTTP)

Scala corre como servicio independiente. Cómo levantarlo:

```bash
java -jar gamestats-analytics.jar serve --port 8080        # local
docker build -t gamestats-scala . && docker run --rm -p 8080:8080 gamestats-scala   # con Docker (arranca en modo servicio)
```

| Método y ruta | Qué hace | Respuestas |
|---|---|---|
| `GET /health` | ¿está vivo? → `{"status":"ok",...}` | 200 |
| `POST /analyze` | cuerpo = eventos; devuelve el análisis | **200** ok · **400** cuerpo vacío · **413** más de 64 MiB · **422** ningún evento válido · **500** error interno |

### Lo que debes enviar

- **Todos los eventos de una partida juntos.** Scala no recuerda nada: si le mandas la partida por pedazos, cada
  llamada ve solo su parte (queda `IN_PROGRESS`, sin duración y sin patrones como `COMEBACK`).
- Una partida por petición es lo más simple y se paraleliza bien (varias partidas = varias goroutines llamando a Scala).
- **Formato recomendado: `{"events":[ ... ]}`.** También acepta NDJSON o un arreglo JSON, pero con NDJSON los
  eventos no pueden contener saltos de línea (si te llegan con formato bonito, se rompe).
- **Reenvía los bytes originales de cada evento** (`json.RawMessage`), sin re-serializar: es más rápido y no se pierde nada.
- No tienes que ordenar por timestamp ni quitar duplicados: Scala deduplica por `eventId` y ordena. (Quitar duplicados en Go igual ahorra ancho de banda.)

### Cuándo reintentar

Scala es **idempotente** (misma entrada → misma salida), así que reintentar es seguro.

| Resultado | Qué hacer |
|---|---|
| error de red, timeout, **5xx** | reintentar con espera creciente (200 ms, 400 ms, 800 ms…) |
| **400 / 413 / 422** | **no** reintentar: es un error de tu envío; loguear y revisar |

### Qué devuelve (respuesta real, recortada)

```json
{
  "schemaVersion": "1.0",
  "invalidEvents": [],
  "matches": [
    {
      "matchId": "edge-football-comeback",
      "gameId": "head_football",
      "status": "FINISHED",
      "startedAt": "2026-10-07T19:30:00Z",
      "finishedAt": "2026-10-07T19:31:00Z",
      "eventCount": 19,
      "duplicatesDropped": 0,
      "matchMetrics": { "durationSeconds": 60, "eventsPerMinute": 19, "totalShots": 10, "totalGoals": 5 },
      "players": [
        { "playerId": "p1",
          "metrics": { "eventCount": 7, "shots": 4, "goals": 2, "shotEffectiveness": 0.5 },
          "distributions": { "shotTypes": { "GROUND": 3, "HEADER": 1 } },
          "crossEventMetrics": {} }
      ],
      "patterns": [
        { "pattern": "COMEBACK", "playerId": "p2",
          "description": "p2 remontó una desventaja de 2 goles y ganó 3-2",
          "details": { "maxDeficit": 2, "winnerFinalGoals": 3, "rivalFinalGoals": 2 } }
      ],
      "warnings": []
    }
  ]
}
```

Esquema completo: `docs/results.schema.json`. **Go no necesita entender las métricas**: basta con leer `matches[].matchId`
y guardar cada partida tal cual (como `json.RawMessage`) para servirla en la consulta. Si quieres mostrar un resumen,
`status` y `patterns` son lo más útil.

`status` vale `FINISHED` (hubo inicio y fin), `IN_PROGRESS` (solo inicio) o `UNKNOWN`. Los `warnings` son avisos no fatales
(falta MATCH_FINISHED, payload ilegible, juego sin analizador…); conviene mostrarlos.

---

## 4. El evento (Envelope) y su validación

Todos los eventos tienen el mismo "sobre"; lo propio de cada juego va dentro de `data`.

```json
{
  "eventId": "3f2b8c1e-7a4d-4e5b-9c10-1a2b3c4d5e6f",
  "timestamp": "2026-10-07T19:30:00.000Z",
  "gameId": "snake",
  "gameVersion": "1.0.0",
  "matchId": "snake-001",
  "playerId": "p1",
  "type": "ACTION",
  "action": "EAT_FOOD",
  "data": { "foodType": "APPLE", "points": 10, "currentLength": 4, "coords": { "x": 5, "y": 8 } }
}
```

### Qué debe rechazar Go

| Regla | Detalle |
|---|---|
| Los 9 campos existen | `eventId, timestamp, gameId, gameVersion, matchId, playerId, type, action, data` |
| Los 8 textos no están vacíos | ni `""` ni solo espacios (`playerId` es `"SYSTEM"` en eventos del servidor) |
| `eventId` es un UUID | formato 8-4-4-4-12 en hexadecimal |
| `timestamp` es ISO 8601 | con o sin fracción de segundos, con `Z` o con offset (`-06:00`) |
| `data` es un **objeto** JSON | no `null`, ni texto, ni arreglo |
| No hay campos de más | el esquema dice `additionalProperties: false` |
| Los tipos son los correctos | un `eventId` numérico es inválido |

### Qué NO debe hacer Go (importante para la extensibilidad)

- **No validar `gameId` ni `action` contra una lista.** Un juego nuevo (ajedrez, cartas…) debe entrar sin tocar Go.
- **No decodificar `data` a structs de un juego.** Déjalo como `json.RawMessage`. Eso es trabajo de Scala.
- **No modificar el evento** antes de reenviarlo.

Los eventos inválidos **no detienen nada**: se cuentan, se loguean con el motivo (`log/slog`) y se guardan los últimos N
en memoria para consultarlos (`GET /invalid`). Lo exige el enunciado ("registrándolos o rechazándolos").

### Código de referencia (probado)

```go
type Envelope struct {
	EventID     string          `json:"eventId"`
	Timestamp   string          `json:"timestamp"`
	GameID      string          `json:"gameId"`
	GameVersion string          `json:"gameVersion"`
	MatchID     string          `json:"matchId"`
	PlayerID    string          `json:"playerId"`
	Type        string          `json:"type"`
	Action      string          `json:"action"`
	Data        json.RawMessage `json:"data"` // NO se decodifica: es propio de cada juego
}

// Event es un evento ya validado, junto con los bytes originales que se reenviarán a Scala.
type Event struct {
	Envelope
	Time time.Time       // timestamp parseado, en UTC
	Raw  json.RawMessage // evento original, sin modificar
}

var uuidRe = regexp.MustCompile(`^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$`)

// Validate comprueba la estructura del sobre y acumula TODOS los problemas encontrados.
func Validate(raw []byte) (Event, error) {
	var env Envelope
	dec := json.NewDecoder(bytes.NewReader(raw))
	dec.DisallowUnknownFields() // el esquema dice additionalProperties: false
	if err := dec.Decode(&env); err != nil {
		return Event{}, fmt.Errorf("JSON inválido: %w", err)
	}

	var problems []string
	required := []struct{ name, value string }{
		{"eventId", env.EventID}, {"timestamp", env.Timestamp}, {"gameId", env.GameID},
		{"gameVersion", env.GameVersion}, {"matchId", env.MatchID}, {"playerId", env.PlayerID},
		{"type", env.Type}, {"action", env.Action},
	}
	for _, f := range required {
		if strings.TrimSpace(f.value) == "" {
			problems = append(problems, fmt.Sprintf("campo '%s' ausente o vacío", f.name))
		}
	}
	if env.EventID != "" && !uuidRe.MatchString(env.EventID) {
		problems = append(problems, "eventId no es un UUID válido")
	}
	if d := bytes.TrimSpace(env.Data); len(d) == 0 || d[0] != '{' {
		problems = append(problems, "campo 'data' ausente o no es un objeto")
	}

	t, err := time.Parse(time.RFC3339Nano, env.Timestamp) // acepta con o sin fracción y con offset
	if env.Timestamp != "" && err != nil {
		problems = append(problems, fmt.Sprintf("timestamp '%s' no cumple ISO 8601", env.Timestamp))
	}

	if len(problems) > 0 {
		return Event{}, errors.New(strings.Join(problems, "; "))
	}
	return Event{Envelope: env, Time: t.UTC(), Raw: json.RawMessage(bytes.Clone(raw))}, nil
}
```

Scala vuelve a validar el sobre por su cuenta (es más permisivo con campos extra), así que si algo se te escapa no se rompe:
aparece en `invalidEvents` de la respuesta. Pero **Go es la primera línea de defensa**.

---

## 5. La parte delicada: ¿cuándo se manda una partida a Scala?

Como Scala analiza partidas completas, Go debe decidir **cuándo una partida está lista**. Dos trampas:

1. **Los eventos pueden llegar desordenados.** `MATCH_FINISHED` puede llegar *antes* que los últimos disparos.
   Si mandas la partida en cuanto ves `MATCH_FINISHED`, pierdes eventos.
2. **Puede que `MATCH_FINISHED` no llegue nunca** (partida abandonada o fuente caída).
   Si esperas para siempre, la partida nunca se analiza y la memoria crece sin límite.

**Regla propuesta** (todo configurable, sección 8):

| Situación | Cuándo se envía a Scala |
|---|---|
| Llegó `MATCH_FINISHED` | tras **`FLUSH_GRACE`** (p. ej. 2 s) **sin eventos nuevos** de esa partida |
| Nunca llegó `MATCH_FINISHED` | tras **`IDLE_TIMEOUT`** (p. ej. 30 s) sin eventos; el resultado sale `IN_PROGRESS` |
| Llega un evento **tarde**, después de haber enviado | se acumula y se **reenvía la partida completa** (Scala es idempotente; el resultado nuevo reemplaza al anterior) |
| La partida lleva mucho sin actividad (`RETENTION`, p. ej. 10 min) | se libera de memoria |
| Se apaga el servicio | se envía lo pendiente antes de salir |

Los eventos duplicados (misma `eventId`) se ignoran dentro de la partida: la fuente puede reentregar.

---

## 6. Concurrencia: arquitectura propuesta

Un pipeline de etapas conectadas con channels. Cada flecha es un `chan`:

```
    fuente de eventos  (archivo · POST /events)
            │  chan: líneas
            ▼
    ┌────────────────────────────────────┐
    │ N validadores (goroutines)         │
    └────────────────────────────────────┘
            │ ─ inválidos ─► log + últimos N
            │  chan: eventos válidos
            ▼
    ┌────────────────────────────────────┐
    │ DESPACHADOR (1 goroutine)          │
    │ dueño de map[matchId]chan          │
    └────────────────────────────────────┘
       │            │            │      una goroutine
       ▼            ▼            ▼      por partida
    [partida A]  [partida B]  [partida C]
       └────────────┼────────────┘
                    │  chan: lotes (Batch)
                    ▼
    ┌────────────────────────────────────┐
    │ M workers → POST /analyze (Scala)  │
    └────────────────────────────────────┘
            │
            ▼
    ┌────────────────────────────────────┐
    │ almacén de resultados (RWMutex)    │
    └────────────────────────────────────┘
            ▲
            └── API de consulta (GET /matches …)
```

| Etapa | Responsabilidad |
|---|---|
| Lector / `POST /events` | leer eventos (archivo o HTTP) y ponerlos en un channel con tamaño limitado |
| N validadores | pool de goroutines: `Validate`; los válidos siguen, los inválidos se loguean |
| **Despachador** | **una sola goroutine** dueña del `map[matchId]chan Event`; crea una goroutine por partida nueva |
| Goroutine por partida | acumula eventos, deduplica, vigila `MATCH_FINISHED` e inactividad, y emite un `Batch` |
| M workers | toman batches y llaman a Scala (con reintentos); varias partidas se analizan en paralelo |
| Almacén de resultados | `matchId → resultado`; protegido con `sync.RWMutex` (lo leen las consultas) |

**Por qué esta forma:** el estado de cada partida lo toca **una sola goroutine**, así que no necesita mutex ni hay
condiciones de carrera. El mapa de partidas lo toca **solo el despachador**. Lo único compartido es el almacén de
resultados (con `RWMutex`). Es el estilo idiomático de Go: *no compartas memoria para comunicar; comunica para compartir memoria*.

**Limpieza sin carreras:** como el despachador es el único que envía a los channels de partida, también es el único que los cierra:
un `time.Ticker` revisa `lastSeen[matchId]` y cierra el channel de las partidas inactivas más de `RETENTION`. Al cerrarse,
la goroutine de esa partida envía lo pendiente y termina. Así no hay riesgo de enviar a un channel ya cerrado.

### Goroutine por partida (probada con el detector de carreras)

```go
// runMatch es la ÚNICA goroutine que toca el estado de una partida: no necesita mutex.
//   - grace: silencio tras MATCH_FINISHED antes de enviar (espera eventos que llegan tarde o desordenados)
//   - idle:  silencio máximo sin MATCH_FINISHED antes de enviar lo que haya (partida abandonada)
//
// Termina cuando el despachador cierra `in`; antes envía lo pendiente.
func runMatch(id string, in <-chan Event, out chan<- Batch, grace, idle time.Duration) {
	var (
		events   []Event
		seen     = map[string]struct{}{}
		finished bool
		dirty    bool // hay eventos que todavía no se han enviado
	)
	flush := func() {
		if dirty {
			out <- Batch{MatchID: id, Events: slices.Clone(events), Final: finished}
			dirty = false
		}
	}
	timer := time.NewTimer(idle) // Go >= 1.23: Reset es seguro sin vaciar el canal
	defer timer.Stop()

	for {
		select {
		case ev, ok := <-in:
			if !ok {
				flush()
				return
			}
			if _, dup := seen[ev.EventID]; dup {
				continue // reentrega at-least-once
			}
			seen[ev.EventID] = struct{}{}
			events = append(events, ev)
			dirty = true
			if ev.Action == "MATCH_FINISHED" {
				finished = true
			}
			if finished {
				timer.Reset(grace)
			} else {
				timer.Reset(idle)
			}
		case <-timer.C:
			flush()
			timer.Reset(idle)
		}
	}
}
```

`Batch` es `struct { MatchID string; Events []Event; Final bool }`. Usa **Go 1.23 o superior** (y `go 1.23` o más en el `go.mod`): desde esa versión `timer.Reset` es seguro sin vaciar el canal.

### Llamar a Scala (probado)

```go
// analyzeOnce hace UNA llamada a Scala. El llamador decide si reintentar (ver la tabla de la sección 3).
func analyzeOnce(ctx context.Context, httpClient *http.Client, scalaURL string, events []Event) ([]byte, error) {
	// Cuerpo: {"events":[ <evento original>, <evento original>, ... ]}
	var body bytes.Buffer
	body.WriteString(`{"events":[`)
	for i, e := range events {
		if i > 0 {
			body.WriteByte(',')
		}
		body.Write(e.Raw) // bytes originales: no se re-serializa nada
	}
	body.WriteString(`]}`)

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, scalaURL+"/analyze", &body)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := httpClient.Do(req) // httpClient con Timeout (p. ej. 30 s)
	if err != nil {
		return nil, err // red caída o timeout: reintentable
	}
	defer resp.Body.Close()
	data, _ := io.ReadAll(resp.Body)

	switch {
	case resp.StatusCode == http.StatusOK:
		return data, nil // el JSON de resultados
	case resp.StatusCode >= 500:
		return nil, fmt.Errorf("scala %d (reintentable): %s", resp.StatusCode, data)
	default: // 400, 413, 422: reintentar no cambia nada
		return nil, fmt.Errorf("scala %d (NO reintentable): %s", resp.StatusCode, data)
	}
}
```

---

## 7. API de consulta que expone Go (propuesta)

Como Scala no guarda nada, **Go guarda los resultados** y los sirve. Rutas sugeridas (puerto `8081`):

| Ruta | Qué hace | Respuesta |
|---|---|---|
| `POST /events` | recibe eventos (NDJSON, arreglo o `{"events":[...]}`); valida **de forma síncrona**, procesa **de forma asíncrona** | `202 {"accepted":N,"invalid":M}` |
| `GET /matches` | lista resumida: `matchId`, `gameId`, `status`, patrones detectados | 200 |
| `GET /matches/{matchId}` | el resultado completo de Scala para esa partida | 200 / 404 si aún no está analizada |
| `GET /invalid` | últimos eventos rechazados con su motivo | 200 |
| `GET /stats` | contadores: recibidos, válidos, inválidos, partidas activas, analizadas, errores hacia Scala | 200 |
| `GET /health` | para Docker/CI | 200 |

Detalles que evitan problemas: si el channel de entrada se llena, haz **contrapresión** (que `POST /events` espere con un límite
de tiempo y responda `503`) en vez de crecer sin límite; y usa `signal.NotifyContext` para un **apagado ordenado**
(cerrar la entrada → esperar que cada partida envíe lo pendiente → salir).

---

## 8. Configuración (variables de entorno)

| Variable | Valor por defecto sugerido | Qué es |
|---|---|---|
| `HTTP_ADDR` | `:8081` | dónde escucha Go |
| `SCALA_URL` | `http://localhost:8080` (en compose: `http://scala:8080`) | dirección del módulo Scala |
| `FLUSH_GRACE` | `2s` | espera tras `MATCH_FINISHED` |
| `IDLE_TIMEOUT` | `30s` | espera sin `MATCH_FINISHED` |
| `RETENTION` | `10m` | cuánto se recuerda una partida inactiva |
| `VALIDATORS` / `SCALA_WORKERS` | `4` / `4` | tamaño de los pools |
| `INVALID_KEEP` | `1000` | cuántos inválidos se guardan para consulta |

---

## 9. Pruébalo ya (Scala está listo)

Scala y su simulador viven en la carpeta `gamestats-scala/` (README propio). Con el jar construido (`sbt assembly`) o con Docker:

```bash
# 1) generar eventos de prueba (con desorden y duplicados a propósito)
java -jar gamestats-analytics.jar simulate --matches 3 --seed 2026 --shuffle --duplicates --edge-cases --out eventos.ndjson

# 2) levantar Scala como servicio
java -jar gamestats-analytics.jar serve --port 8080

# 3) probarlo a mano (en PowerShell usa curl.exe, no curl)
curl.exe http://localhost:8080/health
curl.exe -X POST --data-binary "@eventos.ndjson" http://localhost:8080/analyze
```

Ya hay archivos listos en `examples/`: `edge-cases.ndjson` (escenarios con respuesta conocida) y `sample-matches.ndjson`.
Opciones útiles del simulador: `--games snake,agario,battleship,head_football`, `--matches N`, `--seed N`,
`--shuffle` (desordena), `--duplicates` (repite ~5 % de eventos), `--edge-cases` (casos para reglas especiales), `--format json|ndjson`.

**Resultados que debes obtener con `edge-cases.ndjson`** (sirven como prueba de integración):

| `matchId` | Debe salir |
|---|---|
| `edge-battleship-streak` | 2 patrones `DEADLY_STREAK` de `p1` (rachas de 3 y 4 impactos) |
| `edge-football-comeback` | 1 patrón `COMEBACK` de `p2` (ganó 3-2 tras ir 0-2) |
| `edge-football-no-comeback` y `edge-football-incomplete` | `patterns` vacío (el segundo, además, `IN_PROGRESS`) |
| `edge-agario-window` | `massGainedWithin10sOfSplit` = 150 en `usr_1` |
| `edge-snake-route` | `avgDirectionChangesBetweenFood` = 2 en `p1` |
| `edge-new-game-chess` | `FINISHED` con un `warning` "sin analizador específico" (prueba que un juego nuevo entra sin tocar nada) |

**Prueba de integración completa:** con `--shuffle --duplicates`, el resultado que obtengas pasando por Go tiene que
ser **idéntico** al de analizar el archivo directo con `analyze` (salvo `duplicatesDropped`, que será 0 si Go ya quitó los repetidos).
Yo lo verifiqué con un pipeline Go equivalente: 941 eventos, 3 líneas corruptas añadidas a propósito → 3 inválidos detectados,
19 partidas, 0 fallos, resultados iguales.

### Eventos que genera el simulador (solo informativo — Go no depende de esto)

| `gameId` | Acciones principales (`action`) |
|---|---|
| `snake` | `CHANGE_DIRECTION`, `EAT_FOOD`, `COLLISION` |
| `agario` | `SPLIT`, `EAT_PLAYER` |
| `battleship` | `FIRE_SHOT` |
| `head_football` | `KICK`, `GOAL` |
| (todos) | `PLAYER_JOINED`, `MATCH_STARTED` (`playerId`=`SYSTEM`), `MATCH_FINISHED` (`playerId`=`SYSTEM`) |

---

## 10. Docker, compose y CI/CD

> Nota: el Dockerfile de Scala **todavía no se ha construido con Docker** (probé por separado sus pasos: `sbt test assembly`
> y `java -jar … serve`). Haz `docker build` pronto para detectar cualquier detalle.

El `Dockerfile` de Scala ya arranca en **modo servicio** (`serve --host 0.0.0.0 --port 8080`) e incluye un `HEALTHCHECK`.

**Dockerfile de Go (multi-stage)**

```dockerfile
FROM golang:1.24 AS build
WORKDIR /src
COPY go.mod go.sum* ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/gamestats-go ./cmd/gamestats

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /out/gamestats-go /gamestats-go
EXPOSE 8081
ENTRYPOINT ["/gamestats-go"]
```

**docker-compose.yml** (raíz del repo; `go/` y `scala/` son las carpetas de cada módulo)

```yaml
services:
  scala:
    build: ./scala              # su HEALTHCHECK ya consulta /health
    expose: ["8080"]
  go:
    build: ./go
    environment:
      HTTP_ADDR: ":8081"
      SCALA_URL: "http://scala:8080"
    ports: ["8081:8081"]
    depends_on:
      scala:
        condition: service_healthy   # Go arranca cuando Scala ya responde
```

Aun así, haz que el cliente de Go **reintente** ante errores de conexión: Scala puede reiniciarse en cualquier momento.

**CI (GitHub Actions) — esqueleto de las fases del enunciado: compilar, probar, construir imágenes**

```yaml
name: ci
on: [push, pull_request]
jobs:
  go:
    runs-on: ubuntu-latest
    defaults: { run: { working-directory: go } }
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-go@v5
        with: { go-version: "1.24" }
      - run: go vet ./...
      - run: go test -race ./...
  scala:
    runs-on: ubuntu-latest
    defaults: { run: { working-directory: scala } }
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: "21" }
      - uses: sbt/setup-sbt@v1
      - run: sbt test assembly
  images:
    needs: [go, scala]
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - run: docker compose build
      - run: docker compose up -d --wait   # espera los healthchecks: prueba de humo
      - run: docker compose down
```

Estructura de repositorio sugerida:

```
gamestats/
├── go/            cmd/gamestats/main.go · internal/{event,ingest,pipeline,scala,store,api}/ · Dockerfile · go.mod
├── scala/         (el proyecto sbt de gamestats-scala tal cual)
├── docker-compose.yml
├── .github/workflows/ci.yml
└── docs/          formato de eventos, decisiones, resultados.schema.json
```

---

## 11. Pruebas que debería tener el módulo Go

Ejecútalas siempre con `go test -race ./...`: el detector de carreras es la prueba de que la concurrencia está bien hecha.

- **Validación** (tabla de casos): evento válido; cada campo faltante o vacío; UUID malo; timestamp malo; `data` que no es objeto; campo extra; `gameId` nuevo (debe **aceptarse**).
- **Goroutine por partida:** eventos desordenados con `MATCH_FINISHED` primero; duplicados; evento tardío tras el envío (debe salir un segundo lote **completo**); partida sin fin (sale por inactividad con `Final=false`); cierre del channel (envía lo pendiente).
- **Despachador:** eventos de varias partidas mezcladas llegan a la partida correcta.
- **Cliente de Scala** con `httptest.NewServer`: reintenta ante 503, **no** reintenta ante 422.
- **Integración:** simulador → Go → Scala real con `--shuffle --duplicates`, comparando contra `analyze` directo.

---

## 12. Checklist de entrega del módulo Go

- [ ] Recibe eventos por HTTP y por archivo
- [ ] Valida el Envelope; los inválidos se loguean con motivo y se pueden consultar
- [ ] Un juego nuevo (`gameId` desconocido) entra sin cambios
- [ ] Agrupa por `matchId` y sigue inicio/fin de cada partida
- [ ] Varias partidas y juegos en paralelo con goroutines y channels; `go test -race` limpio
- [ ] Envía partidas completas a Scala con reintentos; sin pérdida por eventos desordenados o tardíos
- [ ] Guarda resultados y expone la consulta (`/matches`, `/matches/{id}`)
- [ ] Apagado ordenado sin perder partidas pendientes
- [ ] Dockerfile multi-stage, `docker-compose.yml`, CI/CD en GitHub Actions
- [ ] Despliegue de la versión funcional en el entorno acordado
- [ ] Documentación de instalación, ejecución y despliegue; justificación imperativo/concurrente vs. funcional

---

## 13. Decisiones a confirmar entre los dos

1. **Canal Go → Scala:** esta guía propone **HTTP** (`POST /analyze`), que ya está implementado en Scala. Hay que dejarlo documentado como decisión del equipo (§7 del enunciado).
2. **Quién expone la consulta de resultados:** propuesta = Go (porque es quien guarda estado). Scala ya devuelve el JSON completo en cada análisis.
3. **Valores de `FLUSH_GRACE`, `IDLE_TIMEOUT` y `RETENTION`** (sección 5): los de la tabla son un punto de partida.
4. **Rigor del esquema:** Go rechaza campos extra (`additionalProperties: false`); Scala los tolera. Si prefieren que Go también los tolere, es un cambio de una línea (`DisallowUnknownFields`).
5. **Fecha de entrega:** el enunciado dice «domingo 18 de Setiembre de 2026», pero ese 18 cae **viernes**; el domingo 18 es en **octubre**. Conviene confirmarlo con el profesor.
