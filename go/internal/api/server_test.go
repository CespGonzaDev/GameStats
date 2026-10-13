package api

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"gamestats/internal/pipeline"
	"gamestats/internal/scala"
	"gamestats/internal/store"
)

func setupTestServer() (*Server, *store.Store, *pipeline.Pipeline, func()) {
	scalaServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(`{
			"schemaVersion": "1.0",
			"matches": [
				{
					"matchId": "m-api-test",
					"gameId": "snake",
					"status": "FINISHED",
					"eventCount": 2,
					"patterns": [{"pattern": "SPEED_RUN"}]
				}
			]
		}`))
	}))

	s := store.NewStore(50)
	sc := scala.NewClient(scala.Config{BaseURL: scalaServer.URL})
	p := pipeline.NewPipeline(pipeline.Config{
		QueueSize:     100,
		NumValidators: 2,
		NumWorkers:    2,
		FlushGrace:    20 * time.Millisecond,
		IdleTimeout:   50 * time.Millisecond,
		Retention:     1 * time.Second,
	}, s, sc)

	srv := NewServer(Config{Addr: ":0"}, s, p)

	cleanup := func() {
		p.Stop()
		scalaServer.Close()
	}

	return srv, s, p, cleanup
}

func TestAPI_HealthAndStats(t *testing.T) {
	srv, _, _, cleanup := setupTestServer()
	defer cleanup()

	// 1. GET /health
	req := httptest.NewRequest(http.MethodGet, "/health", nil)
	rr := httptest.NewRecorder()
	srv.httpServer.Handler.ServeHTTP(rr, req)

	if rr.Code != http.StatusOK {
		t.Fatalf("GET /health devolvió %d", rr.Code)
	}

	// 2. GET /stats
	req = httptest.NewRequest(http.MethodGet, "/stats", nil)
	rr = httptest.NewRecorder()
	srv.httpServer.Handler.ServeHTTP(rr, req)

	if rr.Code != http.StatusOK {
		t.Fatalf("GET /stats devolvió %d", rr.Code)
	}

	var stats map[string]any
	if err := json.Unmarshal(rr.Body.Bytes(), &stats); err != nil {
		t.Fatalf("error parseando JSON de stats: %v", err)
	}
	if _, ok := stats["uptime"]; !ok {
		t.Errorf("stats debe contener uptime")
	}
}

func TestAPI_EventsIngestionAndMatchQuery(t *testing.T) {
	srv, _, _, cleanup := setupTestServer()
	defer cleanup()

	// Enviar dos eventos mediante POST /events (NDJSON)
	body := []byte(`{"eventId":"11111111-1111-1111-1111-111111111111","timestamp":"2026-10-07T19:30:00Z","gameId":"snake","gameVersion":"1.0","matchId":"m-api-test","playerId":"p1","type":"ACTION","action":"EAT_FOOD","data":{}}` + "\n" +
		`{"eventId":"22222222-2222-2222-2222-222222222222","timestamp":"2026-10-07T19:30:01Z","gameId":"snake","gameVersion":"1.0","matchId":"m-api-test","playerId":"SYSTEM","type":"LIFECYCLE","action":"MATCH_FINISHED","data":{}}`)

	req := httptest.NewRequest(http.MethodPost, "/events", bytes.NewReader(body))
	rr := httptest.NewRecorder()
	srv.httpServer.Handler.ServeHTTP(rr, req)

	if rr.Code != http.StatusAccepted {
		t.Fatalf("POST /events devolvió %d, body: %s", rr.Code, rr.Body.String())
	}

	// Esperar que la partida se procese, despache y guarde en el store
	time.Sleep(100 * time.Millisecond)

	// GET /matches
	req = httptest.NewRequest(http.MethodGet, "/matches", nil)
	rr = httptest.NewRecorder()
	srv.httpServer.Handler.ServeHTTP(rr, req)

	if rr.Code != http.StatusOK {
		t.Fatalf("GET /matches devolvió %d", rr.Code)
	}

	var list []store.MatchSummary
	if err := json.Unmarshal(rr.Body.Bytes(), &list); err != nil {
		t.Fatalf("error parseando lista de matches: %v", err)
	}
	if len(list) != 1 || list[0].MatchID != "m-api-test" {
		t.Fatalf("se esperaba encontrar la partida m-api-test en /matches: %+v", list)
	}

	// GET /matches/m-api-test
	req = httptest.NewRequest(http.MethodGet, "/matches/m-api-test", nil)
	rr = httptest.NewRecorder()
	srv.httpServer.Handler.ServeHTTP(rr, req)

	if rr.Code != http.StatusOK {
		t.Fatalf("GET /matches/m-api-test devolvió %d", rr.Code)
	}

	// GET /matches/no-existe -> 404
	req = httptest.NewRequest(http.MethodGet, "/matches/no-existe", nil)
	rr = httptest.NewRecorder()
	srv.httpServer.Handler.ServeHTTP(rr, req)

	if rr.Code != http.StatusNotFound {
		t.Errorf("GET /matches/no-existe debió devolver 404, devolvió %d", rr.Code)
	}
}

func TestAPI_InvalidEventsEndpoint(t *testing.T) {
	srv, _, _, cleanup := setupTestServer()
	defer cleanup()

	// Enviar evento malformado
	body := []byte(`{"eventId":"not-a-uuid","timestamp":"invalid","gameId":"snake","gameVersion":"1.0","matchId":"m-bad","playerId":"p1","type":"ACTION","action":"EAT_FOOD","data":{}}`)
	req := httptest.NewRequest(http.MethodPost, "/events", bytes.NewReader(body))
	rr := httptest.NewRecorder()
	srv.httpServer.Handler.ServeHTTP(rr, req)

	if rr.Code != http.StatusAccepted {
		t.Fatalf("POST /events devolvió %d", rr.Code)
	}

	time.Sleep(50 * time.Millisecond)

	// GET /invalid
	req = httptest.NewRequest(http.MethodGet, "/invalid?limit=10", nil)
	rr = httptest.NewRecorder()
	srv.httpServer.Handler.ServeHTTP(rr, req)

	if rr.Code != http.StatusOK {
		t.Fatalf("GET /invalid devolvió %d", rr.Code)
	}

	var invalids []store.InvalidRecord
	if err := json.Unmarshal(rr.Body.Bytes(), &invalids); err != nil {
		t.Fatalf("error parseando JSON de invalid: %v", err)
	}
	if len(invalids) == 0 {
		t.Errorf("se esperaba al menos 1 evento inválido registrado")
	}
}
