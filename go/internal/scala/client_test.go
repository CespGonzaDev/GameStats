package scala

import (
	"context"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
	"time"

	"gamestats/internal/event"
)

func dummyEvent() event.Event {
	raw := []byte(`{"eventId":"3f2b8c1e-7a4d-4e5b-9c10-1a2b3c4d5e6f","timestamp":"2026-10-07T19:30:00Z","gameId":"snake","gameVersion":"1.0","matchId":"m-1","playerId":"p1","type":"ACTION","action":"EAT_FOOD","data":{}}`)
	ev, _ := event.Validate(raw)
	return ev
}

func TestClient_Analyze_Success(t *testing.T) {
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/analyze" {
			t.Errorf("ruta inesperada: %s", r.URL.Path)
		}
		if r.Header.Get("Content-Type") != "application/json" {
			t.Errorf("Content-Type inesperado: %s", r.Header.Get("Content-Type"))
		}
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(`{"status":"ok"}`))
	}))
	defer ts.Close()

	client := NewClient(Config{
		BaseURL: ts.URL,
	})

	resp, err := client.Analyze(context.Background(), []event.Event{dummyEvent()})
	if err != nil {
		t.Fatalf("error inesperado: %v", err)
	}
	if string(resp) != `{"status":"ok"}` {
		t.Errorf("respuesta inesperada: %s", string(resp))
	}
}

func TestClient_Analyze_RetryOn503(t *testing.T) {
	var attempts int32
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		att := atomic.AddInt32(&attempts, 1)
		if att < 3 {
			w.WriteHeader(http.StatusServiceUnavailable)
			w.Write([]byte(`{"error":"temporarily unavailable"}`))
			return
		}
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(`{"status":"recovered"}`))
	}))
	defer ts.Close()

	client := NewClient(Config{
		BaseURL:    ts.URL,
		MaxRetries: 4,
		BaseDelay:  10 * time.Millisecond,
	})

	resp, err := client.Analyze(context.Background(), []event.Event{dummyEvent()})
	if err != nil {
		t.Fatalf("debió recuperarse tras reintentos: %v", err)
	}
	if string(resp) != `{"status":"recovered"}` {
		t.Errorf("respuesta inesperada: %s", string(resp))
	}
	if atomic.LoadInt32(&attempts) != 3 {
		t.Errorf("se esperaban 3 intentos, ocurrieron %d", attempts)
	}
}

func TestClient_Analyze_NoRetryOn422(t *testing.T) {
	var attempts int32
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt32(&attempts, 1)
		w.WriteHeader(http.StatusUnprocessableEntity)
		w.Write([]byte(`{"error":"invalid envelope"}`))
	}))
	defer ts.Close()

	client := NewClient(Config{
		BaseURL:    ts.URL,
		MaxRetries: 4,
		BaseDelay:  10 * time.Millisecond,
	})

	_, err := client.Analyze(context.Background(), []event.Event{dummyEvent()})
	if err == nil {
		t.Fatalf("se esperaba error inmediato con 422, pero no ocurrió")
	}
	if atomic.LoadInt32(&attempts) != 1 {
		t.Errorf("no debió reintentar ante 422: intentos realizados %d", attempts)
	}
}

func TestClient_Health(t *testing.T) {
	ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/health" {
			w.WriteHeader(http.StatusOK)
			w.Write([]byte(`{"status":"ok"}`))
		}
	}))
	defer ts.Close()

	client := NewClient(Config{BaseURL: ts.URL})
	err := client.Health(context.Background())
	if err != nil {
		t.Fatalf("Health check falló: %v", err)
	}
}
