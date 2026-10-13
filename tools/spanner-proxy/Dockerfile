FROM golang:1.24.4-alpine AS build

WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY main.go ./
COPY main_test.go ./
RUN CGO_ENABLED=0 go test ./...
RUN CGO_ENABLED=0 GOOS=linux go build -trimpath -o /spanner-proxy .

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /spanner-proxy /spanner-proxy
EXPOSE 8666 8474
ENTRYPOINT ["/spanner-proxy"]
