# GameStats — Módulo analítico (Scala) y simulador

Parte de Santiago del proyecto GameStats: **simulador de eventos sintéticos** + **módulo analítico funcional**
que recibe los eventos que entrega el módulo Go, calcula métricas, detecta patrones y devuelve JSON.

- Scala 3.3.4 (LTS) · sbt 1.10 · circe (JSON) · scalatest · JDK 17+ (probado con 21)
- Código del núcleo **sin `var`, sin colecciones mutables, sin bucles imperativos** (lo verifica una prueba automática).
- 75 pruebas unitarias y de integración (incluye un servidor HTTP real con peticiones concurrentes).
- Para quien construye el módulo Go: **`docs/GUIA_MODULO_GO.md`**.

## Uso rápido

```bash
sbt test                       # pruebas
sbt assembly                   # fat-jar: target/scala-3.3.4/gamestats-analytics.jar

# 1) generar eventos (misma semilla = mismos datos)
java -jar target/scala-3.3.4/gamestats-analytics.jar simulate --matches 3 --seed 42 --out events.ndjson
#    opciones: --games snake,agario,battleship,head_football  --edge-cases  --shuffle  --duplicates
#              --format ndjson|json  --start 2026-10-07T19:30:00Z

# 2) analizar (archivo o stdin con "-")
java -jar target/scala-3.3.4/gamestats-analytics.jar analyze events.ndjson --out results.json
cat events.ndjson | java -jar target/scala-3.3.4/gamestats-analytics.jar analyze -

# 3) como servicio HTTP (lo que usa el módulo Go)
java -jar target/scala-3.3.4/gamestats-analytics.jar serve --port 8080
curl -X POST --data-binary @events.ndjson http://localhost:8080/analyze     # en PowerShell: curl.exe
curl http://localhost:8080/health
```

`serve` expone `GET /health` y `POST /analyze` (200 análisis · 400 cuerpo vacío · 413 > 64 MiB · 422 ningún evento
válido · 500 error interno). No guarda estado: cada petición se analiza por separado y reintentar es seguro.
Opciones: `--host` (por defecto `127.0.0.1`), `--port` (o variable `PORT`, por defecto 8080), `--threads`.

Docker (la imagen arranca por defecto en modo servicio, puerto 8080, con `HEALTHCHECK`):

```bash
docker build -t gamestats-scala .
docker run --rm -p 8080:8080 gamestats-scala
# o como herramienta de línea de comandos (se reemplaza el comando):
docker run --rm -v "$PWD:/data" gamestats-scala analyze events.ndjson --out results.json
```

En `examples/` hay datos listos: `edge-cases.ndjson` (escenarios guionizados), `sample-matches.ndjson`
y `edge-cases.results.json` (la salida correspondiente).

## Arquitectura

```
src/main/scala/gamestats/
  model/       Envelope (data: Json), constantes de acciones, tipos de resultado        (inmutables)
  parsing/     EventParser (validación del Envelope, Either), EnvelopeCodec
  analytics/   Timeline (dedup + orden), Metrics, Payloads, GameAnalyzer/GameRegistry,
               MatchAnalysis (orquestador) y games/{Battleship,HeadFootball,Snake,Agario}
  report/      ReportJson (contrato de salida)
  server/      Routes (enrutamiento puro) y AnalyticsServer (HTTP con el servidor del JDK)
  sim/         Gen/Rng (aleatoriedad pura con semilla), un generador por juego, EdgeCases, Simulator
  Main.scala   CLI (analyze, simulate, serve) — con server/, la única capa con efectos (E/S)
```

Flujo: `texto → EventParser → MatchAnalysis.analyze → ReportJson`. `MatchAnalysis` agrupa por `matchId`, descarta
duplicados por `eventId`, ordena por timestamp, calcula las métricas globales y delega en el `GameAnalyzer` del juego.

**Extensibilidad (restricción del inciso A):** un `gameId` sin analizador se procesa igual (métricas globales + una
advertencia). Un juego nuevo = un objeto que extiende `GameAnalyzer` + `GameRegistry.default.register(...)`;
no se toca el parseo, el orquestador ni el JSON de salida (hay una prueba que lo demuestra con ajedrez).

## Mapa contra el documento

| Inciso | Dónde |
|---|---|
| A. Envelope / JSON Schema | `parsing/EventParser.scala` (valida), `parsing/EnvelopeCodec.scala` (escribe); el simulador genera eventos que validan contra el esquema |
| B. Juegos y eventos | `sim/{Snake,Agario,Battleship,HeadFootball}Sim.scala` generan los eventos y payloads de la tabla |
| C.1 Por jugador | Battleship: `accuracy`, `avgDecisionSeconds` · Head Football: `shotEffectiveness`, distribución `shotTypes` |
| C.2 Por partida | Global: `durationSeconds`, `eventsPerMinute` · Snake: `peakScore` · Agar.io: `peakMass` |
| C.3 Cruzadas | Snake: `avgDirectionChangesBetweenFood` · Agar.io: `riskConversionFactor` (+ `massGainedWithin10sOfSplit`) |
| C.4 Patrones | `COMEBACK` (Head Football) y `DEADLY_STREAK` (Battleship) |
| Contrato de resultados | `docs/results.schema.json` (JSON Schema draft-07; la salida real valida contra él) |

## Decisiones y supuestos a confirmar con Daniel

1. **Canal Go → Scala: HTTP (implementado).** Go hace `POST /analyze` con **todos los eventos de una partida**
   (`{"events":[...]}`, NDJSON o arreglo JSON) y recibe el JSON de resultados. Sigue disponible el modo archivo/stdin
   (`analyze`). Falta que el equipo lo deje documentado como decisión (§7 del enunciado).
2. **Orden y duplicados.** Scala no asume que los eventos lleguen ordenados ni sin repetir: deduplica por `eventId`
   y ordena por `timestamp` (estable). Convendría que Go igualmente los entregue agrupados por `matchId`.
3. **`gameId` exactos:** `snake`, `agario`, `battleship`, `head_football` (se comparan sin distinguir mayúsculas).
   Acciones usadas: `FIRE_SHOT`, `KICK`, `GOAL`, `EAT_FOOD`, `CHANGE_DIRECTION`, `COLLISION`, `SPLIT`, `EAT_PLAYER`
   y los de ciclo de vida. `CHANGE_DIRECTION` no aparece en la tabla del inciso B pero sí lo exige la métrica de Snake.
4. **Head Football:** cada `KICK` cuenta como un tiro; el goleador es `data.scorerId` y debe coincidir con el
   `playerId` de sus `KICK`. El marcador se recalcula desde los `GOAL` (si `currentScore` no coincide, solo advertencia).
5. **Tiempo de decisión (Battleship):** segundos entre el disparo anterior de la partida (o `MATCH_STARTED`) y el
   disparo del jugador.
6. **`DEADLY_STREAK`:** impactos consecutivos del propio jugador desde el último fallo **o hundimiento**; se detecta
   cuando son ≥3 y el último hunde un barco (`shipSunk != null`). Un hundimiento cierra la racha.
7. **`COMEBACK`:** el ganador final llegó a ir perdiendo por ≥2 goles contra el mejor rival, y existe
   `MATCH_FINISHED`. Empates o partidas sin cerrar no cuentan.
8. **Agar.io:** ventana de 10 s inclusiva; cada `EAT_PLAYER` se atribuye solo al `SPLIT` más reciente del mismo
   jugador. `riskConversionFactor` = masa ganada en ventana / masa total arriesgada (`massBefore` de sus splits).
9. **Snake:** eficiencia = cantidad de `CHANGE_DIRECTION` entre `EAT_FOOD` sucesivos (menos giros = ruta más
   directa). `peakScore` = máximo entre los puntos acumulados y el `finalScore` de la colisión.
10. Un payload que no se pueda decodificar no tumba el análisis: se omite y queda como advertencia en el reporte.

## Agregar un juego nuevo

```scala
object Ajedrez extends GameAnalyzer:
  val gameId = "ajedrez"
  def analyze(ctx: MatchContext): GameAnalysis =
    GameAnalysis(matchMetrics = Map("moves" -> ctx.events.count(_.action == "MOVE_PIECE").toDouble))

val registry = GameRegistry.default.register(Ajedrez)   // MatchAnalysis.analyze(events, registry)
```

## Notas

- Si `sbt` falla con `InvalidPathException ... unmappable characters` al listar carpetas con tildes (Linux con
  locale ASCII), ejecutar con `LC_ALL=C.UTF-8`.
