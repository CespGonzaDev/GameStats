package api

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"strconv"
	"time"

	"gamestats/internal/ingest"
	"gamestats/internal/pipeline"
	"gamestats/internal/store"
)

// Server expone la API HTTP de ingesta y consulta de GameStats.
type Server struct {
	httpServer *http.Server
	store      *store.Store
	pipeline   *pipeline.Pipeline
}

// Config configura el servidor HTTP.
type Config struct {
	Addr string
}

// NewServer construye las rutas y middlewares del servidor HTTP.
func NewServer(cfg Config, s *store.Store, p *pipeline.Pipeline) *Server {
	if cfg.Addr == "" {
		cfg.Addr = ":8081"
	}

	srv := &Server{
		store:    s,
		pipeline: p,
	}

	mux := http.NewServeMux()

	// Registro de rutas
	mux.HandleFunc("POST /events", srv.handleEvents)
	mux.HandleFunc("GET /matches", srv.handleMatches)
	mux.HandleFunc("GET /matches/{matchId}", srv.handleMatchByID)
	mux.HandleFunc("GET /invalid", srv.handleInvalid)
	mux.HandleFunc("GET /stats", srv.handleStats)
	mux.HandleFunc("GET /health", srv.handleHealth)

	// Middlewares globales de logging y recovery
	handler := recoveryMiddleware(loggingMiddleware(mux))

	srv.httpServer = &http.Server{
		Addr:              cfg.Addr,
		Handler:           handler,
		ReadHeaderTimeout: 5 * time.Second,
		IdleTimeout:       60 * time.Second,
	}

	return srv
}

// Start inicia la escucha HTTP (bloqueante).
func (s *Server) Start() error {
	slog.Info("iniciando servidor HTTP de GameStats", "addr", s.httpServer.Addr)
	if err := s.httpServer.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		return err
	}
	return nil
}

// Shutdown efectúa el apagado ordenado del servidor HTTP.
func (s *Server) Shutdown(ctx context.Context) error {
	return s.httpServer.Shutdown(ctx)
}

// POST /events - Ingesta de eventos (síncrona para enqueue, asíncrona para análisis)
func (s *Server) handleEvents(w http.ResponseWriter, r *http.Request) {
	// Límite de 32MB para prevenir ataques de desbordamiento de memoria
	r.Body = http.MaxBytesReader(w, r.Body, 32*1024*1024)
	defer r.Body.Close()

	ctx, cancel := context.WithTimeout(r.Context(), 10*time.Second)
	defer cancel()

	count, err := ingest.ProcessReader(ctx, r.Body, s.pipeline)
	if err != nil {
		if errors.Is(err, pipeline.ErrQueueFull) {
			writeJSON(w, http.StatusServiceUnavailable, map[string]string{
				"error": "cola de eventos saturada temporalmente (contrapresión)",
			})
			return
		}
		writeJSON(w, http.StatusBadRequest, map[string]string{
			"error": "error procesando formato de eventos: " + err.Error(),
		})
		return
	}

	writeJSON(w, http.StatusAccepted, map[string]any{
		"status":   "accepted",
		"received": count,
	})
}

// GET /matches - Listado de resumen de partidas
func (s *Server) handleMatches(w http.ResponseWriter, r *http.Request) {
	matches := s.store.ListMatches()
	writeJSON(w, http.StatusOK, matches)
}

// GET /matches/{matchId} - Resultado analítico detallado de Scala para una partida
func (s *Server) handleMatchByID(w http.ResponseWriter, r *http.Request) {
	matchID := r.PathValue("matchId")
	if matchID == "" {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "matchId requerido"})
		return
	}

	raw, found := s.store.GetMatch(matchID)
	if !found {
		writeJSON(w, http.StatusNotFound, map[string]string{
			"error":   "partida no encontrada o aún en procesamiento",
			"matchId": matchID,
		})
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(raw)
}

// GET /invalid - Consulta de los últimos eventos descartados con motivo
func (s *Server) handleInvalid(w http.ResponseWriter, r *http.Request) {
	limit := 50
	if lStr := r.URL.Query().Get("limit"); lStr != "" {
		if l, err := strconv.Atoi(lStr); err == nil && l > 0 {
			limit = l
		}
	}

	invalidList := s.store.ListInvalid(limit)
	writeJSON(w, http.StatusOK, invalidList)
}

// GET /stats - Estadísticas operativas del servicio
func (s *Server) handleStats(w http.ResponseWriter, r *http.Request) {
	stats := s.store.Stats()
	writeJSON(w, http.StatusOK, stats)
}

// GET /health - Verificación de salud (liveness probe)
func (s *Server) handleHealth(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, map[string]string{
		"status": "ok",
	})
}

func writeJSON(w http.ResponseWriter, status int, data any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(data)
}

// Middlewares
func loggingMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		rw := &statusRecorder{ResponseWriter: w, status: http.StatusOK}
		next.ServeHTTP(rw, r)
		slog.Debug("HTTP request",
			"method", r.Method,
			"path", r.URL.Path,
			"status", rw.status,
			"duration", time.Since(start))
	})
}

func recoveryMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer func() {
			if rec := recover(); rec != nil {
				slog.Error("panic capturado en HTTP handler", "error", rec)
				writeJSON(w, http.StatusInternalServerError, map[string]string{
					"error": "error interno del servidor",
				})
			}
		}()
		next.ServeHTTP(w, r)
	})
}

type statusRecorder struct {
	http.ResponseWriter
	status int
}

func (r *statusRecorder) WriteHeader(status int) {
	r.status = status
	r.ResponseWriter.WriteHeader(status)
}
