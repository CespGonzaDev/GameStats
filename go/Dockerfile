# ==========================================
# Etapa 1: Compilación de alto rendimiento
# ==========================================
FROM golang:1.24-alpine AS build

WORKDIR /src

# Instalar certificados CA mínimos
RUN apk --no-cache add ca-certificates

COPY go.mod go.sum* ./
RUN go mod download

COPY . .

# Compilación estática sin CGO para binario ultra ligero y seguro
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -ldflags="-s -w" -o /out/gamestats-go ./cmd/gamestats

# ==========================================
# Etapa 2: Imagen de ejecución mínima (distroless o scratch)
# ==========================================
FROM alpine:3.20

# Crear usuario sin privilegios
RUN adduser -D -u 10001 gamestats
USER gamestats

WORKDIR /app
COPY --from=build /out/gamestats-go /app/gamestats-go

EXPOSE 8081

ENTRYPOINT ["/app/gamestats-go"]
