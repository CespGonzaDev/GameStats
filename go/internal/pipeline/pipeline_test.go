package pipeline

import (
	"context"
	"fmt"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"

	"gamestats/internal/scala"
	"gamestats/internal/store"
)

func makeTestRawEvent(eventID, matchID, action string) []byte {
	return []byte(fmt.Sprintf(`{
		"eventId": "%s",
		"timestamp": "2026-10-07T19:30:00.000Z",
		"gameId": "snake",
		"gameVersion": "1.0.0",
		"matchId": "%s",
		"playerId": "p1",
		"type": "ACTION",
		"action": "%s",
		"data": {"points": 10}
	}`, eventID, matchID, action))
}

func TestPipeline_OutOfOrderAndDuplicates(t *testing.T) {
	var analyzedCalls int32
	var lastEventCount int32

	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt32(&analyzedCalls, 1)
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(`{
			"schemaVersion": "1.0",
			"matches": [{"matchId": "m-disorder", "gameId": "snake", "status": "FINISHED", "eventCount": 3, "patterns": []}]
		}`))
	}))
	defer ts.Close()

	s := store.NewStore(100)
	sc := scala.NewClient(scala.Config{BaseURL: ts.URL})

	cfg := Config{
		QueueSize:     100,
		NumValidators: 2,
		NumWorkers:    2,
		FlushGrace:    80 * time.Millisecond,
		IdleTimeout:   500 * time.Millisecond,
		Retention:     2 * time.Second,
	}

	p := NewPipeline(cfg, s, sc)
	defer p.Stop()

	// Enviar MATCH_FINISHED PRIMERO (desorden intencional)
	err := p.Submit(context.Background(), makeTestRawEvent("11111111-1111-1111-1111-111111111111", "m-disorder", "MATCH_FINISHED"))
	if err != nil {
		t.Fatalf("submit falló: %v", err)
	}

	// Enviar evento de acción después
	err = p.Submit(context.Background(), makeTestRawEvent("22222222-2222-2222-2222-222222222222", "m-disorder", "EAT_FOOD"))
	if err != nil {
		t.Fatalf("submit falló: %v", err)
	}

	// Enviar duplicado del mismo evento (debe ignorarse)
	err = p.Submit(context.Background(), makeTestRawEvent("22222222-2222-2222-2222-222222222222", "m-disorder", "EAT_FOOD"))
	if err != nil {
		t.Fatalf("submit falló: %v", err)
	}

	// Enviar tercer evento antes de que expire FlushGrace
	time.Sleep(20 * time.Millisecond)
	err = p.Submit(context.Background(), makeTestRawEvent("33333333-3333-3333-3333-333333333333", "m-disorder", "CHANGE_DIRECTION"))
	if err != nil {
		t.Fatalf("submit falló: %v", err)
	}

	// Esperar que transcurra FlushGrace
	time.Sleep(150 * time.Millisecond)

	calls := atomic.LoadInt32(&analyzedCalls)
	if calls != 1 {
		t.Fatalf("se esperaba exactamente 1 despacho a Scala, pero hubo %d", calls)
	}

	raw, found := s.GetMatch("m-disorder")
	if !found || len(raw) == 0 {
		t.Errorf("la partida no fue registrada en el store")
	}
	_ = lastEventCount
}

func TestPipeline_IncompleteMatch_IdleTimeout(t *testing.T) {
	var analyzedCalls int32

	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt32(&analyzedCalls, 1)
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(`{
			"schemaVersion": "1.0",
			"matches": [{"matchId": "m-incomplete", "gameId": "snake", "status": "IN_PROGRESS", "eventCount": 1, "patterns": []}]
		}`))
	}))
	defer ts.Close()

	s := store.NewStore(100)
	sc := scala.NewClient(scala.Config{BaseURL: ts.URL})

	cfg := Config{
		QueueSize:     100,
		NumValidators: 2,
		NumWorkers:    2,
		FlushGrace:    50 * time.Millisecond,
		IdleTimeout:   100 * time.Millisecond, // Disparo por timeout de inactividad
		Retention:     1 * time.Second,
	}

	p := NewPipeline(cfg, s, sc)
	defer p.Stop()

	// Enviar solo 1 evento sin MATCH_FINISHED
	_ = p.Submit(context.Background(), makeTestRawEvent("44444444-4444-4444-4444-444444444444", "m-incomplete", "EAT_FOOD"))

	// Esperar a que venza IdleTimeout
	time.Sleep(180 * time.Millisecond)

	if atomic.LoadInt32(&analyzedCalls) != 1 {
		t.Errorf("debió despacharse por timeout de inactividad, llamadas: %d", analyzedCalls)
	}

	match, ok := s.GetMatch("m-incomplete")
	if !ok || len(match) == 0 {
		t.Errorf("el resultado de la partida incompleta no se guardó en el almacén")
	}
}

func TestPipeline_MultipleParallelMatches(t *testing.T) {
	var dispatchedBatches int32

	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt32(&dispatchedBatches, 1)
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(`{"schemaVersion": "1.0", "matches": []}`))
	}))
	defer ts.Close()

	s := store.NewStore(100)
	sc := scala.NewClient(scala.Config{BaseURL: ts.URL})

	cfg := Config{
		QueueSize:     500,
		NumValidators: 4,
		NumWorkers:    4,
		FlushGrace:    50 * time.Millisecond,
		IdleTimeout:   100 * time.Millisecond,
		Retention:     1 * time.Second,
	}

	p := NewPipeline(cfg, s, sc)

	// Emitir eventos para 5 partidas distintas intercaladas
	for m := 1; m <= 5; m++ {
		matchID := fmt.Sprintf("match-%d", m)
		ev1 := makeTestRawEvent(fmt.Sprintf("%08d-0000-0000-0000-000000000001", m), matchID, "MATCH_STARTED")
		ev2 := makeTestRawEvent(fmt.Sprintf("%08d-0000-0000-0000-000000000002", m), matchID, "MATCH_FINISHED")
		_ = p.Submit(context.Background(), ev1)
		_ = p.Submit(context.Background(), ev2)
	}

	// Apagado ordenado: debe asegurar que las 5 partidas sean procesadas sin fugas
	p.Stop()

	if atomic.LoadInt32(&dispatchedBatches) != 5 {
		t.Fatalf("se esperaban 5 despachos, se obtuvieron: %d", dispatchedBatches)
	}
}
