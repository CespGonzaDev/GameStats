package pipeline

import (
	"context"
	"errors"
	"log/slog"
	"slices"
	"sync"
	"sync/atomic"
	"time"

	"gamestats/internal/event"
	"gamestats/internal/scala"
	"gamestats/internal/store"
)

var (
	ErrQueueFull  = errors.New("cola de eventos saturada (contrapresión)")
	ErrTerminating = errors.New("pipeline en proceso de apagado")
)

// Batch representa un conjunto de eventos acumulados para una partida específica listos para ser analizados.
type Batch struct {
	MatchID string
	Events  []event.Event
	Final   bool
}

// Config define los parámetros operativos y de concurrencia del pipeline.
type Config struct {
	QueueSize      int           // Capacidad del buffer de entrada (rawChan)
	NumValidators  int           // Goroutines del pool de validación de Envelopes
	NumWorkers     int           // Goroutines del pool de despacho HTTP a Scala
	FlushGrace     time.Duration // Tiempo de silencio tras MATCH_FINISHED antes de despachar
	IdleTimeout    time.Duration // Tiempo máximo sin MATCH_FINISHED antes de despachar (partida incompleta)
	Retention      time.Duration // Tiempo de inactividad antes de liberar la goroutine de partida de la memoria
	TickerInterval time.Duration // Intervalo para el recolector de partidas inactivas
}

// DefaultConfig provee valores óptimos según los requisitos del proyecto.
func DefaultConfig() Config {
	return Config{
		QueueSize:      5000,
		NumValidators:  4,
		NumWorkers:     4,
		FlushGrace:     2 * time.Second,
		IdleTimeout:    30 * time.Second,
		Retention:      10 * time.Minute,
		TickerInterval: 10 * time.Second,
	}
}

// Pipeline orquesta la ingestión concurrente, validación, agrupación por partida y despacho.
type Pipeline struct {
	cfg         Config
	store       *store.Store
	scalaClient *scala.Client

	rawChan   chan []byte
	validChan chan event.Event
	batchChan chan Batch

	ctx    context.Context
	cancel context.CancelFunc

	stopping int32 // flag atómico

	validatorsWg sync.WaitGroup
	dispatcherWg sync.WaitGroup
	workersWg    sync.WaitGroup
	matchesWg    sync.WaitGroup

	// Contador de partidas activas en memoria
	activeMatches int32
}

// NewPipeline instancia y arranca todos los componentes concurrentes del pipeline.
func NewPipeline(cfg Config, s *store.Store, sc *scala.Client) *Pipeline {
	if cfg.QueueSize <= 0 {
		cfg.QueueSize = 5000
	}
	if cfg.NumValidators <= 0 {
		cfg.NumValidators = 4
	}
	if cfg.NumWorkers <= 0 {
		cfg.NumWorkers = 4
	}
	if cfg.FlushGrace <= 0 {
		cfg.FlushGrace = 2 * time.Second
	}
	if cfg.IdleTimeout <= 0 {
		cfg.IdleTimeout = 30 * time.Second
	}
	if cfg.Retention <= 0 {
		cfg.Retention = 10 * time.Minute
	}
	if cfg.TickerInterval <= 0 {
		cfg.TickerInterval = 5 * time.Second
	}

	ctx, cancel := context.WithCancel(context.Background())

	p := &Pipeline{
		cfg:         cfg,
		store:       s,
		scalaClient: sc,
		rawChan:     make(chan []byte, cfg.QueueSize),
		validChan:   make(chan event.Event, cfg.QueueSize),
		batchChan:   make(chan Batch, cfg.QueueSize),
		ctx:         ctx,
		cancel:      cancel,
	}

	// Registrar proveedor de partidas activas para el store
	s.SetActiveMatchesProvider(p.ActiveMatchesCount)

	p.start()
	return p
}

func (p *Pipeline) start() {
	// 1. Iniciar pool de validadores
	for i := 0; i < p.cfg.NumValidators; i++ {
		p.validatorsWg.Add(1)
		go p.validatorWorker(i)
	}

	// Goroutine auxiliar que cierra validChan cuando todos los validadores terminan
	go func() {
		p.validatorsWg.Wait()
		close(p.validChan)
	}()

	// 2. Iniciar el despachador único
	p.dispatcherWg.Add(1)
	go p.dispatcher()

	// 3. Iniciar pool de workers HTTP hacia Scala
	for i := 0; i < p.cfg.NumWorkers; i++ {
		p.workersWg.Add(1)
		go p.scalaWorker(i)
	}
}

// Submit encola un evento crudo para su procesamiento concurrente.
// Si la cola está llena, aplica contrapresión respetando el timeout o context del llamador.
func (p *Pipeline) Submit(ctx context.Context, raw []byte) error {
	if atomic.LoadInt32(&p.stopping) == 1 {
		return ErrTerminating
	}

	select {
	case p.rawChan <- raw:
		p.store.IncReceivedEvents(1)
		return nil
	case <-ctx.Done():
		return ctx.Err()
	default:
		// La cola está al límite, intentar con un breve margen antes de descartar
		select {
		case p.rawChan <- raw:
			p.store.IncReceivedEvents(1)
			return nil
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(50 * time.Millisecond):
			return ErrQueueFull
		}
	}
}

// validatorWorker ejecuta la validación del sobre en paralelo.
func (p *Pipeline) validatorWorker(id int) {
	defer p.validatorsWg.Done()

	for raw := range p.rawChan {
		ev, err := event.Validate(raw)
		if err != nil {
			slog.Warn("evento inválido rechazado", "validador", id, "error", err, "raw", string(raw))
			p.store.AddInvalid(string(raw), err.Error())
			continue
		}

		p.store.IncValidEvents(1)
		p.validChan <- ev
	}
}

// dispatcher es la ÚNICA goroutine dueña del mapa de canales por partida.
// Esto garantiza cero bloqueos de mutex y ausencia total de carreras de datos.
func (p *Pipeline) dispatcher() {
	defer p.dispatcherWg.Done()

	matches := make(map[string]chan event.Event)
	lastSeen := make(map[string]time.Time)

	ticker := time.NewTicker(p.cfg.TickerInterval)
	defer ticker.Stop()

	closeMatch := func(id string) {
		if ch, ok := matches[id]; ok {
			close(ch)
			delete(matches, id)
			delete(lastSeen, id)
			atomic.AddInt32(&p.activeMatches, -1)
		}
	}

	for {
		select {
		case ev, ok := <-p.validChan:
			if !ok {
				// Señal de apagado: cerrar todos los canales de partidas activas
				for id := range matches {
					closeMatch(id)
				}
				// Esperar a que todas las goroutines de partidas vacíen sus lotes
				p.matchesWg.Wait()
				// Ahora es completamente seguro cerrar batchChan
				close(p.batchChan)
				return
			}

			ch, exists := matches[ev.MatchID]
			if !exists {
				ch = make(chan event.Event, 500)
				matches[ev.MatchID] = ch
				atomic.AddInt32(&p.activeMatches, 1)

				p.matchesWg.Add(1)
				go p.runMatch(ev.MatchID, ch)
			}

			ch <- ev
			lastSeen[ev.MatchID] = time.Now()

		case now := <-ticker.C:
			// Limpieza de partidas que han superado el tiempo de retención
			for id, seenTime := range lastSeen {
				if now.Sub(seenTime) > p.cfg.Retention {
					slog.Info("liberando partida inactiva por retención", "matchId", id, "inactiva_por", now.Sub(seenTime))
					closeMatch(id)
				}
			}
		}
	}
}

// runMatch gestiona de forma aislada el ciclo de vida de una partida concreta.
// No requiere cerrojos (mutex) porque su estado pertenece en exclusiva a esta goroutine.
func (p *Pipeline) runMatch(id string, in <-chan event.Event) {
	defer p.matchesWg.Done()

	var (
		events   []event.Event
		seen     = make(map[string]struct{})
		finished bool
		dirty    bool
	)

	flush := func() {
		if dirty {
			p.batchChan <- Batch{
				MatchID: id,
				Events:  slices.Clone(events),
				Final:   finished,
			}
			dirty = false
		}
	}

	timer := time.NewTimer(p.cfg.IdleTimeout)
	defer timer.Stop()

	for {
		select {
		case ev, ok := <-in:
			if !ok {
				flush()
				return
			}

			if _, dup := seen[ev.EventID]; dup {
				continue // Deduplicación at-least-once
			}
			seen[ev.EventID] = struct{}{}
			events = append(events, ev)
			dirty = true

			if ev.Action == "MATCH_FINISHED" {
				finished = true
			}

			if finished {
				timer.Reset(p.cfg.FlushGrace)
			} else {
				timer.Reset(p.cfg.IdleTimeout)
			}

		case <-timer.C:
			flush()
			timer.Reset(p.cfg.IdleTimeout)
		}
	}
}

// scalaWorker procesa los lotes de partidas llamando al servicio funcional de Scala.
func (p *Pipeline) scalaWorker(id int) {
	defer p.workersWg.Done()

	for batch := range p.batchChan {
		p.store.IncBatchesDispatched()
		p.store.IncScalaCalls()

		resp, err := p.scalaClient.Analyze(p.ctx, batch.Events)
		if err != nil {
			slog.Error("error al despachar partida a Scala",
				"worker", id,
				"matchId", batch.MatchID,
				"eventCount", len(batch.Events),
				"error", err)
			p.store.IncScalaErrors()
			continue
		}

		if err := p.store.SaveScalaResponse(resp); err != nil {
			slog.Error("error al parsear respuesta analítica de Scala",
				"worker", id,
				"matchId", batch.MatchID,
				"error", err)
		} else {
			slog.Info("partida analizada exitosamente por Scala",
				"matchId", batch.MatchID,
				"eventos", len(batch.Events),
				"final", batch.Final)
		}
	}
}

// ActiveMatchesCount devuelve la cantidad actual de partidas procesándose en memoria.
func (p *Pipeline) ActiveMatchesCount() int {
	return int(atomic.LoadInt32(&p.activeMatches))
}

// Stop efectúa un apagado ordenado (graceful shutdown):
// 1. Deja de recibir nuevos eventos.
// 2. Drena validadores.
// 3. El despachador cierra canales de partidas.
// 4. Cada partida emite su lote final pendiente.
// 5. Los workers analizan y guardan todos los lotes pendientes.
// 6. Termina sin fugas de goroutines ni pérdida de datos.
func (p *Pipeline) Stop() {
	if !atomic.CompareAndSwapInt32(&p.stopping, 0, 1) {
		return
	}

	slog.Info("iniciando apagado ordenado del pipeline de ingestión...")

	// Cerrar canal de entrada: provocará el drenado ordenado en cascada
	close(p.rawChan)

	// Esperar que el despachador y partidas finalicen
	p.dispatcherWg.Wait()

	// Esperar que los workers de Scala despachen los últimos batches
	p.workersWg.Wait()

	p.cancel()
	slog.Info("pipeline de ingestión apagado completamente.")
}
