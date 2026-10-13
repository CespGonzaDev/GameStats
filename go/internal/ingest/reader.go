package ingest

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"os"

	"gamestats/internal/pipeline"
)

// ProcessReader detecta automáticamente el formato (NDJSON, arreglo JSON o {"events":[...]})
// y desglosa cada evento crudo enviándolo al pipeline sin alterar sus bytes.
func ProcessReader(ctx context.Context, r io.Reader, p *pipeline.Pipeline) (int, error) {
	br := bufio.NewReader(r)

	// Leer primer byte no blanco para determinar si es NDJSON o JSON global
	for {
		b, err := br.Peek(1)
		if err != nil {
			if err == io.EOF {
				return 0, nil
			}
			return 0, err
		}
		if b[0] == ' ' || b[0] == '\t' || b[0] == '\r' || b[0] == '\n' {
			_, _ = br.ReadByte()
			continue
		}
		break
	}

	firstByte, err := br.Peek(1)
	if err != nil {
		return 0, err
	}

	count := 0

	// Caso 1: Arreglo JSON directo `[...]`
	if firstByte[0] == '[' {
		dec := json.NewDecoder(br)
		tok, err := dec.Token() // '['
		if err != nil {
			return 0, fmt.Errorf("error leyendo apertura de arreglo JSON: %w", err)
		}
		_ = tok

		for dec.More() {
			var raw json.RawMessage
			if err := dec.Decode(&raw); err != nil {
				return count, fmt.Errorf("error decodificando elemento de arreglo: %w", err)
			}
			if err := p.Submit(ctx, raw); err != nil {
				return count, err
			}
			count++
		}
		return count, nil
	}

	// Caso 2: Objeto envolvente `{"events":[...]}`
	// O Caso 3: NDJSON línea por línea
	// Leemos la primera línea para diferenciar si es {"events" o un evento NDJSON individual
	line, err := br.ReadBytes('\n')
	if err != nil && err != io.EOF {
		return 0, err
	}

	trimmed := bytes.TrimSpace(line)
	if len(trimmed) > 0 {
		if bytes.HasPrefix(trimmed, []byte(`{"events"`)) || bytes.HasPrefix(trimmed, []byte(`{ "events"`)) {
			// Es un objeto con propiedad "events": [...]
			// Unir lo leído y decodificar la propiedad
			fullReader := io.MultiReader(bytes.NewReader(line), br)
			var wrapper struct {
				Events []json.RawMessage `json:"events"`
			}
			if err := json.NewDecoder(fullReader).Decode(&wrapper); err != nil {
				return 0, fmt.Errorf("error decodificando wrapper de eventos: %w", err)
			}
			for _, raw := range wrapper.Events {
				if err := p.Submit(ctx, raw); err != nil {
					return count, err
				}
				count++
			}
			return count, nil
		}

		// Si no era wrapper, es la primera línea de un stream NDJSON
		if err := p.Submit(ctx, trimmed); err != nil {
			return count, err
		}
		count++
	}

	// Continuar leyendo el resto línea por línea como NDJSON
	scanner := bufio.NewScanner(br)
	// Permitir líneas grandes de hasta 5MB por si un evento lleva data extensa
	buf := make([]byte, 64*1024)
	scanner.Buffer(buf, 5*1024*1024)

	for scanner.Scan() {
		l := bytes.TrimSpace(scanner.Bytes())
		if len(l) == 0 {
			continue
		}
		if err := p.Submit(ctx, bytes.Clone(l)); err != nil {
			return count, err
		}
		count++
	}

	if err := scanner.Err(); err != nil {
		return count, fmt.Errorf("error escaneando NDJSON: %w", err)
	}

	return count, nil
}

// IngestFile abre un archivo desde disco y procesa sus eventos hacia el pipeline.
func IngestFile(ctx context.Context, filePath string, p *pipeline.Pipeline) (int, error) {
	file, err := os.Open(filePath)
	if err != nil {
		return 0, fmt.Errorf("no se pudo abrir el archivo de eventos '%s': %w", filePath, err)
	}
	defer file.Close()

	return ProcessReader(ctx, file, p)
}
