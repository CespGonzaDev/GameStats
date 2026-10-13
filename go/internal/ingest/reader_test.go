package ingest

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"gamestats/internal/pipeline"
	"gamestats/internal/scala"
	"gamestats/internal/store"
)

func sampleEvent(id string) string {
	return `{"eventId":"` + id + `","timestamp":"2026-10-07T19:30:00Z","gameId":"snake","gameVersion":"1.0","matchId":"m1","playerId":"p1","type":"ACTION","action":"EAT_FOOD","data":{}}`
}

func setupTestPipeline() (*pipeline.Pipeline, *store.Store, func()) {
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(`{"schemaVersion":"1.0","matches":[]}`))
	}))

	s := store.NewStore(100)
	sc := scala.NewClient(scala.Config{BaseURL: ts.URL})
	p := pipeline.NewPipeline(pipeline.Config{
		QueueSize:     100,
		NumValidators: 2,
		NumWorkers:    2,
		FlushGrace:    20 * time.Millisecond,
		IdleTimeout:   50 * time.Millisecond,
		Retention:     1 * time.Second,
	}, s, sc)

	cleanup := func() {
		p.Stop()
		ts.Close()
	}
	return p, s, cleanup
}

func TestProcessReader_NDJSON(t *testing.T) {
	p, s, cleanup := setupTestPipeline()
	defer cleanup()

	ndjson := strings.Join([]string{
		sampleEvent("11111111-1111-1111-1111-111111111111"),
		sampleEvent("22222222-2222-2222-2222-222222222222"),
		sampleEvent("33333333-3333-3333-3333-333333333333"),
	}, "\n")

	count, err := ProcessReader(context.Background(), strings.NewReader(ndjson), p)
	if err != nil {
		t.Fatalf("error en ProcessReader NDJSON: %v", err)
	}
	if count != 3 {
		t.Errorf("se esperaban 3 eventos procesados, se obtuvieron: %d", count)
	}

	st := s.Stats()
	if st.ReceivedEvents != 3 {
		t.Errorf("ReceivedEvents en store debió ser 3, fue %d", st.ReceivedEvents)
	}
}

func TestProcessReader_JSONArray(t *testing.T) {
	p, _, cleanup := setupTestPipeline()
	defer cleanup()

	arr := `[` + sampleEvent("11111111-1111-1111-1111-111111111111") + `,` + sampleEvent("22222222-2222-2222-2222-222222222222") + `]`

	count, err := ProcessReader(context.Background(), strings.NewReader(arr), p)
	if err != nil {
		t.Fatalf("error en ProcessReader JSONArray: %v", err)
	}
	if count != 2 {
		t.Errorf("se esperaban 2 eventos procesados, se obtuvieron: %d", count)
	}
}

func TestProcessReader_EventsWrapper(t *testing.T) {
	p, _, cleanup := setupTestPipeline()
	defer cleanup()

	wrapper := `{"events": [` + sampleEvent("11111111-1111-1111-1111-111111111111") + `]}`

	count, err := ProcessReader(context.Background(), strings.NewReader(wrapper), p)
	if err != nil {
		t.Fatalf("error en ProcessReader Wrapper: %v", err)
	}
	if count != 1 {
		t.Errorf("se esperaba 1 evento procesado, se obtuvo: %d", count)
	}
}
