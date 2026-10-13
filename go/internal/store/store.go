package store

import (
	"encoding/json"
	"sync"
	"sync/atomic"
	"time"
)

// MatchSummary es una vista resumida de una partida procesada para GET /matches.
type MatchSummary struct {
	MatchID    string    `json:"matchId"`
	GameID     string    `json:"gameId"`
	Status     string    `json:"status"`
	Patterns   []string  `json:"patterns"`
	EventCount int       `json:"eventCount"`
	UpdatedAt  time.Time `json:"updatedAt"`
}

// MatchRecord almacena tanto el resumen como el payload JSON original devuelto por Scala.
type MatchRecord struct {
	Summary   MatchSummary
	RawResult json.RawMessage
}

// InvalidRecord registra un evento rechazado junto con el motivo y la estampa de descarte.
type InvalidRecord struct {
	Timestamp time.Time `json:"timestamp"`
	Raw       string    `json:"raw"`
	Reason    string    `json:"reason"`
}

// Stats contiene las métricas operativas del servicio Go.
type Stats struct {
	Uptime            string `json:"uptime"`
	ReceivedEvents    int64  `json:"receivedEvents"`
	ValidEvents       int64  `json:"validEvents"`
	InvalidEvents     int64  `json:"invalidEvents"`
	BatchesDispatched int64  `json:"batchesDispatched"`
	MatchesCompleted  int64  `json:"matchesCompleted"`
	ActiveMatches     int    `json:"activeMatches"`
	ScalaCalls        int64  `json:"scalaCalls"`
	ScalaErrors       int64  `json:"scalaErrors"`
	TotalMatchesSaved int    `json:"totalMatchesSaved"`
}

// Store es el almacén central en memoria protegido concurrentemente.
type Store struct {
	matchesMu sync.RWMutex
	matches   map[string]MatchRecord

	invalidMu   sync.RWMutex
	invalidList []InvalidRecord
	invalidCap  int

	activeMatchesMu sync.RWMutex
	activeMatchesFn func() int

	startTime time.Time

	// Contadores atómicos de alta concurrencia
	receivedEvents    int64
	validEvents       int64
	invalidEvents     int64
	batchesDispatched int64
	matchesCompleted  int64
	scalaCalls        int64
	scalaErrors       int64
}

// NewStore inicializa el almacén en memoria con capacidad configurable para eventos inválidos.
func NewStore(invalidCapacity int) *Store {
	if invalidCapacity <= 0 {
		invalidCapacity = 1000
	}
	return &Store{
		matches:     make(map[string]MatchRecord),
		invalidList: make([]InvalidRecord, 0, invalidCapacity),
		invalidCap:  invalidCapacity,
		startTime:   time.Now(),
	}
}

// SetActiveMatchesProvider permite consultar el número de goroutines de partidas vivas en el pipeline.
func (s *Store) SetActiveMatchesProvider(fn func() int) {
	s.activeMatchesMu.Lock()
	defer s.activeMatchesMu.Unlock()
	s.activeMatchesFn = fn
}

// SaveScalaResponse procesa la respuesta devuelta por Scala y almacena/actualiza cada partida analizada.
func (s *Store) SaveScalaResponse(rawResponse []byte) error {
	// Esquema parcial para extraer metadata
	var resp struct {
		Matches []struct {
			MatchID    string `json:"matchId"`
			GameID     string `json:"gameId"`
			Status     string `json:"status"`
			EventCount int    `json:"eventCount"`
			Patterns   []struct {
				Pattern string `json:"pattern"`
			} `json:"patterns"`
		} `json:"matches"`
	}

	if err := json.Unmarshal(rawResponse, &resp); err != nil {
		return err
	}

	s.matchesMu.Lock()
	defer s.matchesMu.Unlock()

	now := time.Now().UTC()
	for _, m := range resp.Matches {
		patternNames := make([]string, 0, len(m.Patterns))
		for _, p := range m.Patterns {
			patternNames = append(patternNames, p.Pattern)
		}

		summary := MatchSummary{
			MatchID:    m.MatchID,
			GameID:     m.GameID,
			Status:     m.Status,
			Patterns:   patternNames,
			EventCount: m.EventCount,
			UpdatedAt:  now,
		}

		s.matches[m.MatchID] = MatchRecord{
			Summary:   summary,
			RawResult: json.RawMessage(rawResponse),
		}

		if m.Status == "FINISHED" {
			atomic.AddInt64(&s.matchesCompleted, 1)
		}
	}
	return nil
}

// GetMatch recupera el JSON completo de una partida por matchId.
func (s *Store) GetMatch(matchID string) (json.RawMessage, bool) {
	s.matchesMu.RLock()
	defer s.matchesMu.RUnlock()
	rec, exists := s.matches[matchID]
	if !exists {
		return nil, false
	}
	return rec.RawResult, true
}

// ListMatches devuelve un listado de resúmenes de todas las partidas procesadas.
func (s *Store) ListMatches() []MatchSummary {
	s.matchesMu.RLock()
	defer s.matchesMu.RUnlock()

	res := make([]MatchSummary, 0, len(s.matches))
	for _, rec := range s.matches {
		res = append(res, rec.Summary)
	}
	return res
}

// AddInvalid almacena un evento corrupto en el buffer circular.
func (s *Store) AddInvalid(raw string, reason string) {
	atomic.AddInt64(&s.invalidEvents, 1)

	s.invalidMu.Lock()
	defer s.invalidMu.Unlock()

	if len(s.invalidList) >= s.invalidCap {
		// Descartar el más antiguo (FIFO)
		s.invalidList = s.invalidList[1:]
	}
	s.invalidList = append(s.invalidList, InvalidRecord{
		Timestamp: time.Now().UTC(),
		Raw:       raw,
		Reason:    reason,
	})
}

// ListInvalid devuelve una copia de los eventos inválidos registrados hasta un límite dado.
func (s *Store) ListInvalid(limit int) []InvalidRecord {
	s.invalidMu.RLock()
	defer s.invalidMu.RUnlock()

	if limit <= 0 || limit > len(s.invalidList) {
		limit = len(s.invalidList)
	}

	res := make([]InvalidRecord, limit)
	copy(res, s.invalidList[len(s.invalidList)-limit:])
	return res
}

// Métodos de incremento atómico
func (s *Store) IncReceivedEvents(n int64) { atomic.AddInt64(&s.receivedEvents, n) }
func (s *Store) IncValidEvents(n int64)    { atomic.AddInt64(&s.validEvents, n) }
func (s *Store) IncBatchesDispatched()     { atomic.AddInt64(&s.batchesDispatched, 1) }
func (s *Store) IncScalaCalls()            { atomic.AddInt64(&s.scalaCalls, 1) }
func (s *Store) IncScalaErrors()           { atomic.AddInt64(&s.scalaErrors, 1) }

// Stats devuelve una instantánea de todas las métricas del sistema.
func (s *Store) Stats() Stats {
	s.matchesMu.RLock()
	totalSaved := len(s.matches)
	s.matchesMu.RUnlock()

	activeMatches := 0
	s.activeMatchesMu.RLock()
	if s.activeMatchesFn != nil {
		activeMatches = s.activeMatchesFn()
	}
	s.activeMatchesMu.RUnlock()

	return Stats{
		Uptime:            time.Since(s.startTime).Truncate(time.Second).String(),
		ReceivedEvents:    atomic.LoadInt64(&s.receivedEvents),
		ValidEvents:       atomic.LoadInt64(&s.validEvents),
		InvalidEvents:     atomic.LoadInt64(&s.invalidEvents),
		BatchesDispatched: atomic.LoadInt64(&s.batchesDispatched),
		MatchesCompleted:  atomic.LoadInt64(&s.matchesCompleted),
		ActiveMatches:     activeMatches,
		ScalaCalls:        atomic.LoadInt64(&s.scalaCalls),
		ScalaErrors:       atomic.LoadInt64(&s.scalaErrors),
		TotalMatchesSaved: totalSaved,
	}
}
