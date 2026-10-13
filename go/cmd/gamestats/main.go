package main

import (
	"context"
	"flag"
	"fmt"
	"log/slog"
	"os"
	"os/signal"
	"strconv"
	"syscall"
	"time"

	"gamestats/internal/api"
	"gamestats/internal/ingest"
	"gamestats/internal/pipeline"
	"gamestats/internal/scala"
	"gamestats/internal/store"
)

func getEnv(key, defaultVal string) string {
	if val := os.Getenv(key); val != "" {
		return val
	}
	return defaultVal
}

func getEnvDuration(key string, defaultVal time.Duration) time.Duration {
	if val := os.Getenv(key); val != "" {
		if d, err := time.ParseDuration(val); err == nil {
			return d
		}
	}
	return defaultVal
}

func getEnvInt(key string, defaultVal int) int {
	if val := os.Getenv(key); val != "" {
		if n, err := strconv.Atoi(val); err == nil {
			return n
		}
	}
	return defaultVal
}

func main() {
	// Parámetros por línea de comandos con fallback a variables de entorno
	addrFlag := flag.String("addr", getEnv("HTTP_ADDR", ":8081"), "Dirección de escucha HTTP (ej. :8081)")
	scalaFlag := flag.String("scala", getEnv("SCALA_URL", "http://localhost:8080"), "URL base del servicio Scala")
	fileFlag := flag.String("file", getEnv("EVENT_FILE", ""), "Archivo opcional de eventos para ingesta directa (.ndjson/.json)")
	graceFlag := flag.Duration("grace", getEnvDuration("FLUSH_GRACE", 2*time.Second), "Ventana de silencio tras MATCH_FINISHED antes de despachar")
	idleFlag := flag.Duration("idle", getEnvDuration("IDLE_TIMEOUT", 30*time.Second), "Timeout sin MATCH_FINISHED antes de despachar partida")
	retentionFlag := flag.Duration("retention", getEnvDuration("RETENTION", 10*time.Minute), "Tiempo de retención de partida inactiva en memoria")
	validatorsFlag := flag.Int("validators", getEnvInt("VALIDATORS", 4), "Número de goroutines validadoras")
	workersFlag := flag.Int("workers", getEnvInt("SCALA_WORKERS", 4), "Número de goroutines despachadoras a Scala")
	invalidCapFlag := flag.Int("invalid-cap", getEnvInt("INVALID_KEEP", 1000), "Capacidad del buffer de eventos inválidos")
	flag.Parse()

	// Logger estructurado
	logger := slog.New(slog.NewTextHandler(os.Stdout, &slog.HandlerOptions{
		Level: slog.LevelInfo,
	}))
	slog.SetDefault(logger)

	slog.Info("=== Iniciando GameStats Ingestor (Go) ===",
		"http_addr", *addrFlag,
		"scala_url", *scalaFlag,
		"validators", *validatorsFlag,
		"scala_workers", *workersFlag,
		"flush_grace", *graceFlag,
		"idle_timeout", *idleFlag,
		"retention", *retentionFlag)

	// Inicialización de componentes
	st := store.NewStore(*invalidCapFlag)
	sc := scala.NewClient(scala.Config{
		BaseURL: *scalaFlag,
	})

	pipeCfg := pipeline.Config{
		QueueSize:     10000,
		NumValidators: *validatorsFlag,
		NumWorkers:    *workersFlag,
		FlushGrace:    *graceFlag,
		IdleTimeout:   *idleFlag,
		Retention:     *retentionFlag,
	}
	pipe := pipeline.NewPipeline(pipeCfg, st, sc)

	srv := api.NewServer(api.Config{
		Addr: *addrFlag,
	}, st, pipe)

	// Contexto de cancelación atado a señales del SO
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	// Iniciar servidor HTTP en segundo plano
	serverErrCh := make(chan error, 1)
	go func() {
		if err := srv.Start(); err != nil {
			serverErrCh <- err
		}
		close(serverErrCh)
	}()

	// Si se especificó un archivo local, procesarlo concurrentemente
	if *fileFlag != "" {
		go func(path string) {
			slog.Info("ingiriendo archivo de eventos especificado", "archivo", path)
			count, err := ingest.IngestFile(ctx, path, pipe)
			if err != nil {
				slog.Error("error durante la ingesta del archivo", "archivo", path, "error", err)
			} else {
				slog.Info("ingesta de archivo finalizada con éxito", "archivo", path, "eventos_ingresados", count)
			}
		}(*fileFlag)
	}

	// Esperar señal de apagado o error del servidor
	select {
	case <-ctx.Done():
		slog.Info("señal de terminación recibida, comenzando apagado ordenado...")
	case err := <-serverErrCh:
		if err != nil {
			slog.Error("fallo inesperado en el servidor HTTP", "error", err)
		}
	}

	// 1. Detener recepción de peticiones HTTP
	shutdownCtx, cancelShutdown := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancelShutdown()
	if err := srv.Shutdown(shutdownCtx); err != nil {
		slog.Warn("error cerrando servidor HTTP", "error", err)
	}

	// 2. Drenar y apagar el pipeline concurrente (vacía partidas pendientes y despacha a Scala)
	pipe.Stop()

	// 3. Imprimir resumen final de estadísticas
	finalStats := st.Stats()
	fmt.Printf("\n=== Resumen de Operación GameStats ===\n"+
		"Uptime:             %s\n"+
		"Eventos Recibidos:  %d\n"+
		"Eventos Válidos:    %d\n"+
		"Eventos Inválidos:  %d\n"+
		"Lotes Despachados:  %d\n"+
		"Partidas Guardadas: %d\n"+
		"Llamadas a Scala:   %d\n"+
		"Errores hacia Scala:%d\n"+
		"======================================\n",
		finalStats.Uptime,
		finalStats.ReceivedEvents,
		finalStats.ValidEvents,
		finalStats.InvalidEvents,
		finalStats.BatchesDispatched,
		finalStats.TotalMatchesSaved,
		finalStats.ScalaCalls,
		finalStats.ScalaErrors,
	)

	slog.Info("proceso GameStats finalizado limpiamente.")
}
