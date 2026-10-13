package main

import (
	"bytes"
	"context"
	"crypto/tls"
	"encoding/binary"
	"io"
	"log"
	"net"
	"net/http"
	"net/http/httputil"
	"net/url"
	"regexp"
	"strings"
	"sync/atomic"
	"time"

	"cloud.google.com/go/spanner/apiv1/spannerpb"
	"golang.org/x/net/http2"
	"golang.org/x/net/http2/h2c"
	"google.golang.org/protobuf/proto"
)

const (
	spannerUpstream     = "http://spanner-emulator:9010"
	executeSQLPath      = "/google.spanner.v1.Spanner/ExecuteSql"
	controlAddress      = ":8474"
	spannerProxyAddress = ":8666"
)

var todoInsertPattern = regexp.MustCompile("(?is)^\\s*INSERT\\s+INTO\\s+[`\"]?todos[`\"]?(?:\\s|\\()")

type spannerProxy struct {
	failTodoInsert atomic.Bool
	ready          atomic.Bool
	reverseProxy   *httputil.ReverseProxy
}

func main() {
	target, err := url.Parse(spannerUpstream)
	if err != nil {
		log.Fatal(err)
	}

	transport := &http2.Transport{
		AllowHTTP: true,
		DialTLSContext: func(ctx context.Context, network, address string, _ *tls.Config) (net.Conn, error) {
			return (&net.Dialer{Timeout: 5 * time.Second}).DialContext(ctx, network, address)
		},
	}
	proxy := &spannerProxy{
		reverseProxy: &httputil.ReverseProxy{
			Rewrite: func(request *httputil.ProxyRequest) {
				request.SetURL(target)
				request.Out.Host = target.Host
			},
			Transport: transport,
		},
	}

	spannerServer := &http.Server{
		Addr:              spannerProxyAddress,
		Handler:           h2c.NewHandler(proxy, &http2.Server{}),
		ReadHeaderTimeout: 5 * time.Second,
	}
	controlServer := &http.Server{
		Addr:              controlAddress,
		Handler:           newControlHandler(proxy),
		ReadHeaderTimeout: 5 * time.Second,
	}

	spannerListener, err := net.Listen("tcp", spannerProxyAddress)
	if err != nil {
		log.Fatal(err)
	}
	controlListener, err := net.Listen("tcp", controlAddress)
	if err != nil {
		log.Fatal(err)
	}
	proxy.ready.Store(true)

	go func() {
		log.Fatal(spannerServer.Serve(spannerListener))
	}()
	log.Fatal(controlServer.Serve(controlListener))
}

func (proxy *spannerProxy) ServeHTTP(writer http.ResponseWriter, request *http.Request) {
	if request.Method == http.MethodPost && request.URL.Path == executeSQLPath {
		body, err := io.ReadAll(request.Body)
		if err != nil {
			http.Error(writer, "failed to read Spanner RPC", http.StatusBadRequest)
			return
		}
		request.Body = io.NopCloser(bytes.NewReader(body))
		if proxy.failTodoInsert.Load() && isTodoInsert(body) {
			writer.Header().Set("Content-Type", "application/grpc")
			writer.Header().Set("grpc-status", "14")
			writer.Header().Set("grpc-message", url.PathEscape("injected todo insert failure"))
			writer.WriteHeader(http.StatusOK)
			return
		}
	}
	proxy.reverseProxy.ServeHTTP(writer, request)
}

func isTodoInsert(frame []byte) bool {
	if len(frame) < 5 || frame[0] != 0 {
		return false
	}
	messageLength := int(binary.BigEndian.Uint32(frame[1:5]))
	if messageLength != len(frame)-5 {
		return false
	}

	request := new(spannerpb.ExecuteSqlRequest)
	if err := proto.Unmarshal(frame[5:], request); err != nil {
		return false
	}
	sql := strings.TrimSpace(request.GetSql())
	return todoInsertPattern.MatchString(sql)
}

func newControlHandler(proxy *spannerProxy) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", func(writer http.ResponseWriter, request *http.Request) {
		if request.Method != http.MethodGet {
			writer.WriteHeader(http.StatusMethodNotAllowed)
			return
		}
		if !proxy.ready.Load() {
			writer.WriteHeader(http.StatusServiceUnavailable)
			return
		}
		writer.WriteHeader(http.StatusNoContent)
	})
	mux.HandleFunc("/faults/insert-into-todos", func(writer http.ResponseWriter, request *http.Request) {
		switch request.Method {
		case http.MethodPost:
			proxy.failTodoInsert.Store(true)
			writer.WriteHeader(http.StatusNoContent)
		case http.MethodDelete:
			proxy.failTodoInsert.Store(false)
			writer.WriteHeader(http.StatusNoContent)
		default:
			writer.Header().Set("Allow", "POST, DELETE")
			writer.WriteHeader(http.StatusMethodNotAllowed)
		}
	})
	return mux
}
