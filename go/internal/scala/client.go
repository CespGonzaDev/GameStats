package scala

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"time"

	"gamestats/internal/event"
)

// Client gestiona las comunicaciones HTTP con el servicio analítico funcional de Scala.
type Client struct {
	baseURL    string
	httpClient *http.Client
	maxRetries int
	baseDelay  time.Duration
}

// Config configura los parámetros del cliente Scala.
type Config struct {
	BaseURL    string
	Timeout    time.Duration
	MaxRetries int
	BaseDelay  time.Duration
}

// NewClient inicializa un cliente con timeouts y configuración de reintentos.
func NewClient(cfg Config) *Client {
	if cfg.BaseURL == "" {
		cfg.BaseURL = "http://localhost:8080"
	}
	if cfg.Timeout <= 0 {
		cfg.Timeout = 30 * time.Second
	}
	if cfg.MaxRetries <= 0 {
		cfg.MaxRetries = 4
	}
	if cfg.BaseDelay <= 0 {
		cfg.BaseDelay = 200 * time.Millisecond
	}

	return &Client{
		baseURL: cfg.BaseURL,
		httpClient: &http.Client{
			Timeout: cfg.Timeout,
		},
		maxRetries: cfg.MaxRetries,
		baseDelay:  cfg.BaseDelay,
	}
}

// Analyze envía un lote de eventos pertenecientes a una partida a Scala mediante POST /analyze.
// Implementa reintentos con backoff exponencial para errores transitorios de red o códigos 5xx.
// No reintenta ante errores de cliente (400, 413, 422).
func (c *Client) Analyze(ctx context.Context, events []event.Event) ([]byte, error) {
	if len(events) == 0 {
		return nil, fmt.Errorf("lote de eventos vacío")
	}

	// Construir payload: {"events":[ <evento1>, <evento2>, ... ]}
	// Usando los bytes originales (Raw) de cada evento sin re-serializar
	var body bytes.Buffer
	body.WriteString(`{"events":[`)
	for i, e := range events {
		if i > 0 {
			body.WriteByte(',')
		}
		body.Write(e.Raw)
	}
	body.WriteString(`]}`)
	payloadBytes := body.Bytes()

	var lastErr error
	delay := c.baseDelay

	for attempt := 0; attempt <= c.maxRetries; attempt++ {
		if attempt > 0 {
			slog.Warn("reintentando llamada a Scala",
				"intento", attempt,
				"max_intentos", c.maxRetries,
				"espera", delay,
				"error_previo", lastErr)

			select {
			case <-time.After(delay):
				delay *= 2
			case <-ctx.Done():
				return nil, fmt.Errorf("cancelado durante reintento: %w", ctx.Err())
			}
		}

		req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.baseURL+"/analyze", bytes.NewReader(payloadBytes))
		if err != nil {
			return nil, fmt.Errorf("error construyendo petición HTTP: %w", err)
		}
		req.Header.Set("Content-Type", "application/json")

		resp, err := c.httpClient.Do(req)
		if err != nil {
			lastErr = fmt.Errorf("error de conexión con Scala: %w", err)
			continue // reintentar ante fallo de conexión / timeout
		}

		data, readErr := io.ReadAll(resp.Body)
		resp.Body.Close()
		if readErr != nil {
			lastErr = fmt.Errorf("error leyendo respuesta de Scala: %w", readErr)
			continue
		}

		switch {
		case resp.StatusCode == http.StatusOK:
			return data, nil

		case resp.StatusCode >= 500:
			// Error interno en Scala, reintentable
			lastErr = fmt.Errorf("scala error %d (reintentable): %s", resp.StatusCode, string(data))
			continue

		default:
			// 400 (Bad Request), 413 (Payload Too Large), 422 (Unprocessable Entity)
			// No reintentar: es un error definitivo en la estructura enviada
			return nil, fmt.Errorf("scala error %d (NO reintentable): %s", resp.StatusCode, string(data))
		}
	}

	return nil, fmt.Errorf("se agotaron los reintentos hacia Scala (%d intentos): %w", c.maxRetries, lastErr)
}

// Health comprueba si el servicio Scala está activo y respondiendo HTTP 200 en GET /health.
func (c *Client) Health(ctx context.Context) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, c.baseURL+"/health", nil)
	if err != nil {
		return err
	}
	resp, err := c.httpClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("scala health devolvió código %d", resp.StatusCode)
	}
	return nil
}
