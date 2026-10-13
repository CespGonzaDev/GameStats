package event

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"regexp"
	"strings"
	"time"
)

// Envelope representa la estructura canónica agnóstica de cualquier evento de GameStats.
// Los campos específicos de cada juego se mantienen encapsulados en Data como json.RawMessage
// para garantizar extensibilidad sin acoplar Go a juegos concretos.
type Envelope struct {
	EventID     string          `json:"eventId"`
	Timestamp   string          `json:"timestamp"`
	GameID      string          `json:"gameId"`
	GameVersion string          `json:"gameVersion"`
	MatchID     string          `json:"matchId"`
	PlayerID    string          `json:"playerId"`
	Type        string          `json:"type"`
	Action      string          `json:"action"`
	Data        json.RawMessage `json:"data"`
}

// Event representa un evento ya validado, con su estampa temporal normalizada a UTC
// y sus bytes crudos originales conservados íntegramente para su despacho a Scala.
type Event struct {
	Envelope
	Time time.Time       `json:"-"`
	Raw  json.RawMessage `json:"-"`
}

var uuidRe = regexp.MustCompile(`^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$`)

// Validate verifica la conformidad del evento contra el JSON Schema canónico:
// 1. Sintaxis JSON válida y sin campos adicionales (additionalProperties: false).
// 2. Presencia de los 9 campos obligatorios.
// 3. Ninguno de los 8 campos de texto está vacío o compuesto solo por espacios en blanco.
// 4. eventId cumple formato UUID v4 / RFC 4122.
// 5. timestamp cumple estándar ISO-8601 / RFC-3339 (con o sin fracciones, con Z o timezone offset).
// 6. data es un objeto JSON {...} válido y no nulo/arreglo/escalar.
// Acumula todos los errores encontrados para un logging exhaustivo.
func Validate(raw []byte) (Event, error) {
	if len(bytes.TrimSpace(raw)) == 0 {
		return Event{}, errors.New("evento vacío")
	}

	var env Envelope
	dec := json.NewDecoder(bytes.NewReader(raw))
	dec.DisallowUnknownFields()
	if err := dec.Decode(&env); err != nil {
		return Event{}, fmt.Errorf("JSON inválido o campos no permitidos: %w", err)
	}

	var problems []string
	requiredFields := []struct {
		name  string
		value string
	}{
		{"eventId", env.EventID},
		{"timestamp", env.Timestamp},
		{"gameId", env.GameID},
		{"gameVersion", env.GameVersion},
		{"matchId", env.MatchID},
		{"playerId", env.PlayerID},
		{"type", env.Type},
		{"action", env.Action},
	}

	for _, f := range requiredFields {
		if strings.TrimSpace(f.value) == "" {
			problems = append(problems, fmt.Sprintf("campo '%s' ausente o vacío", f.name))
		}
	}

	if env.EventID != "" && !uuidRe.MatchString(env.EventID) {
		problems = append(problems, fmt.Sprintf("eventId '%s' no es un UUID válido", env.EventID))
	}

	trimmedData := bytes.TrimSpace(env.Data)
	if len(trimmedData) == 0 || trimmedData[0] != '{' {
		problems = append(problems, "campo 'data' ausente o no es un objeto JSON")
	}

	var parsedTime time.Time
	if env.Timestamp != "" {
		var err error
		parsedTime, err = time.Parse(time.RFC3339Nano, env.Timestamp)
		if err != nil {
			// Intentar con RFC3339 estándar si falló nano
			parsedTime, err = time.Parse(time.RFC3339, env.Timestamp)
		}
		if err != nil {
			problems = append(problems, fmt.Sprintf("timestamp '%s' no cumple formato ISO 8601", env.Timestamp))
		}
	}

	if len(problems) > 0 {
		return Event{}, errors.New(strings.Join(problems, "; "))
	}

	return Event{
		Envelope: env,
		Time:     parsedTime.UTC(),
		Raw:      json.RawMessage(bytes.Clone(raw)),
	}, nil
}
