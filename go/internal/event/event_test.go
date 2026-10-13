package event

import (
	"strings"
	"testing"
)

func validPayload() string {
	return `{
		"eventId": "3f2b8c1e-7a4d-4e5b-9c10-1a2b3c4d5e6f",
		"timestamp": "2026-10-07T19:30:00.000Z",
		"gameId": "snake",
		"gameVersion": "1.0.0",
		"matchId": "snake-001",
		"playerId": "p1",
		"type": "ACTION",
		"action": "EAT_FOOD",
		"data": {"foodType": "APPLE", "points": 10}
	}`
}

func TestValidate_Success(t *testing.T) {
	raw := []byte(validPayload())
	ev, err := Validate(raw)
	if err != nil {
		t.Fatalf("se esperaba evento válido, pero ocurrió error: %v", err)
	}
	if ev.EventID != "3f2b8c1e-7a4d-4e5b-9c10-1a2b3c4d5e6f" {
		t.Errorf("EventID incorrecto: %s", ev.EventID)
	}
	if ev.MatchID != "snake-001" {
		t.Errorf("MatchID incorrecto: %s", ev.MatchID)
	}
	if ev.Time.IsZero() {
		t.Errorf("Time no fue parseado correctamente")
	}
	if len(ev.Raw) == 0 {
		t.Errorf("Raw no debe estar vacío")
	}
}

func TestValidate_Extensibility_NewGameAccepted(t *testing.T) {
	// Un juego completamente nuevo como ajedrez o cartas debe ser aceptado sin tocar Go
	raw := []byte(`{
		"eventId": "a1b2c3d4-e5f6-4a5b-8c9d-0e1f2a3b4c5d",
		"timestamp": "2026-10-07T19:35:00-06:00",
		"gameId": "chess_turbo",
		"gameVersion": "0.1.0-alpha",
		"matchId": "chess-999",
		"playerId": "magnus",
		"type": "MOVE",
		"action": "CASTLING",
		"data": {"from": "e1", "to": "g1", "piecesCaptured": 0}
	}`)
	ev, err := Validate(raw)
	if err != nil {
		t.Fatalf("un juego nuevo no debe ser rechazado por Go: %v", err)
	}
	if ev.GameID != "chess_turbo" || ev.Action != "CASTLING" {
		t.Errorf("valores inesperados en juego nuevo: %+v", ev.Envelope)
	}
}

func TestValidate_InvalidCases(t *testing.T) {
	tests := []struct {
		name          string
		json          string
		expectedError string
	}{
		{
			name:          "JSON malformado",
			json:          `{ "eventId": "bad-json", `,
			expectedError: "JSON inválido",
		},
		{
			name: "Campo adicional no permitido",
			json: `{
				"eventId": "3f2b8c1e-7a4d-4e5b-9c10-1a2b3c4d5e6f",
				"timestamp": "2026-10-07T19:30:00.000Z",
				"gameId": "snake",
				"gameVersion": "1.0.0",
				"matchId": "snake-001",
				"playerId": "p1",
				"type": "ACTION",
				"action": "EAT_FOOD",
				"data": {},
				"extraField": "malicioso"
			}`,
			expectedError: "campos no permitidos",
		},
		{
			name: "UUID inválido",
			json: `{
				"eventId": "no-es-un-uuid-valido",
				"timestamp": "2026-10-07T19:30:00.000Z",
				"gameId": "snake",
				"gameVersion": "1.0.0",
				"matchId": "snake-001",
				"playerId": "p1",
				"type": "ACTION",
				"action": "EAT_FOOD",
				"data": {}
			}`,
			expectedError: "no es un UUID válido",
		},
		{
			name: "Timestamp no ISO 8601",
			json: `{
				"eventId": "3f2b8c1e-7a4d-4e5b-9c10-1a2b3c4d5e6f",
				"timestamp": "2026/10/07 19:30:00",
				"gameId": "snake",
				"gameVersion": "1.0.0",
				"matchId": "snake-001",
				"playerId": "p1",
				"type": "ACTION",
				"action": "EAT_FOOD",
				"data": {}
			}`,
			expectedError: "no cumple formato ISO 8601",
		},
		{
			name: "Campos requeridos vacíos o espacios",
			json: `{
				"eventId": "3f2b8c1e-7a4d-4e5b-9c10-1a2b3c4d5e6f",
				"timestamp": "2026-10-07T19:30:00Z",
				"gameId": "   ",
				"gameVersion": "",
				"matchId": "snake-001",
				"playerId": "p1",
				"type": "ACTION",
				"action": "EAT_FOOD",
				"data": {}
			}`,
			expectedError: "campo 'gameId' ausente o vacío",
		},
		{
			name: "Data ausente o no es objeto (arreglo)",
			json: `{
				"eventId": "3f2b8c1e-7a4d-4e5b-9c10-1a2b3c4d5e6f",
				"timestamp": "2026-10-07T19:30:00Z",
				"gameId": "snake",
				"gameVersion": "1.0.0",
				"matchId": "snake-001",
				"playerId": "p1",
				"type": "ACTION",
				"action": "EAT_FOOD",
				"data": [1, 2, 3]
			}`,
			expectedError: "campo 'data' ausente o no es un objeto JSON",
		},
		{
			name: "Data es escalar primitivo",
			json: `{
				"eventId": "3f2b8c1e-7a4d-4e5b-9c10-1a2b3c4d5e6f",
				"timestamp": "2026-10-07T19:30:00Z",
				"gameId": "snake",
				"gameVersion": "1.0.0",
				"matchId": "snake-001",
				"playerId": "p1",
				"type": "ACTION",
				"action": "EAT_FOOD",
				"data": "no-es-objeto"
			}`,
			expectedError: "campo 'data' ausente o no es un objeto JSON",
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			_, err := Validate([]byte(tc.json))
			if err == nil {
				t.Fatalf("se esperaba error conteniendo '%s', pero no hubo error", tc.expectedError)
			}
			if !strings.Contains(err.Error(), tc.expectedError) {
				t.Errorf("error '%s' no contiene '%s'", err.Error(), tc.expectedError)
			}
		})
	}
}
