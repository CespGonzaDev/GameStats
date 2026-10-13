package store

import (
	"fmt"
	"sync"
	"testing"
)

func TestStore_SaveAndRetrieve(t *testing.T) {
	s := NewStore(10)

	scalaResp := []byte(`{
		"schemaVersion": "1.0",
		"matches": [
			{
				"matchId": "m-123",
				"gameId": "head_football",
				"status": "FINISHED",
				"eventCount": 20,
				"patterns": [{"pattern": "COMEBACK"}]
			}
		]
	}`)

	err := s.SaveScalaResponse(scalaResp)
	if err != nil {
		t.Fatalf("error al guardar respuesta de Scala: %v", err)
	}

	raw, found := s.GetMatch("m-123")
	if !found {
		t.Fatalf("se esperaba encontrar match 'm-123'")
	}
	if len(raw) == 0 {
		t.Errorf("el raw guardado está vacío")
	}

	matches := s.ListMatches()
	if len(matches) != 1 {
		t.Fatalf("se esperaba 1 match en la lista, se obtuvieron %d", len(matches))
	}
	if matches[0].MatchID != "m-123" || matches[0].Status != "FINISHED" || len(matches[0].Patterns) != 1 {
		t.Errorf("datos de resumen inesperados: %+v", matches[0])
	}
}

func TestStore_InvalidBufferRing(t *testing.T) {
	cap := 3
	s := NewStore(cap)

	for i := 1; i <= 5; i++ {
		s.AddInvalid(fmt.Sprintf("raw-%d", i), "error")
	}

	inv := s.ListInvalid(10)
	if len(inv) != 3 {
		t.Fatalf("capacidad del buffer esperada 3, pero se obtuvieron %d", len(inv))
	}
	// Debe contener los últimos 3 (3, 4, 5)
	if inv[0].Raw != "raw-3" || inv[2].Raw != "raw-5" {
		t.Errorf("el buffer circular no preservó los elementos más recientes: %+v", inv)
	}
}

func TestStore_ConcurrentAccess(t *testing.T) {
	s := NewStore(100)
	var wg sync.WaitGroup

	for i := 0; i < 50; i++ {
		wg.Add(1)
		go func(id int) {
			defer wg.Done()
			s.IncReceivedEvents(1)
			s.IncValidEvents(1)
			s.AddInvalid(fmt.Sprintf("raw-%d", id), "err")
			_ = s.Stats()
			_ = s.ListInvalid(10)
			_ = s.SaveScalaResponse([]byte(fmt.Sprintf(`{"matches":[{"matchId":"m-%d","status":"IN_PROGRESS"}]}`, id)))
			_, _ = s.GetMatch(fmt.Sprintf("m-%d", id))
			_ = s.ListMatches()
		}(i)
	}

	wg.Wait()
	st := s.Stats()
	if st.ReceivedEvents != 50 || st.ValidEvents != 50 {
		t.Errorf("contadores atómicos inconsistentes: %+v", st)
	}
}
