package main

import (
	"bytes"
	"encoding/binary"
	"net/http"
	"net/http/httptest"
	"net/http/httputil"
	"net/url"
	"testing"

	"cloud.google.com/go/spanner/apiv1/spannerpb"
	"google.golang.org/protobuf/proto"
)

func TestIsTodoInsert(t *testing.T) {
	tests := []struct {
		name string
		sql  string
		want bool
	}{
		{name: "todo insert", sql: "INSERT INTO todos (title) VALUES ('Buy milk')", want: true},
		{name: "quoted todo insert", sql: `insert into "todos" (title) values ('Buy milk')`, want: true},
		{name: "other table insert", sql: "INSERT INTO users (name) VALUES ('Ada')"},
		{name: "todo query", sql: "SELECT * FROM todos"},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			if got := isTodoInsert(grpcFrame(t, test.sql)); got != test.want {
				t.Fatalf("isTodoInsert() = %t, want %t", got, test.want)
			}
		})
	}
}

func TestIsTodoInsertIgnoresUnsupportedFrames(t *testing.T) {
	if isTodoInsert([]byte{1, 0, 0, 0, 0}) {
		t.Fatal("compressed frames must not be treated as a matching insert")
	}
	if isTodoInsert([]byte{0, 0, 0, 0, 1, 0}) {
		t.Fatal("malformed frames must not be treated as a matching insert")
	}
}

func TestProxyFailsOnlyTodoInsertRPCsWhenEnabled(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		writer.WriteHeader(http.StatusNoContent)
	}))
	defer upstream.Close()

	target, err := url.Parse(upstream.URL)
	if err != nil {
		t.Fatal(err)
	}
	proxy := &spannerProxy{
		reverseProxy: httputil.NewSingleHostReverseProxy(target),
	}
	proxy.failTodoInsert.Store(true)

	insertRequest := httptest.NewRequest(
		http.MethodPost,
		executeSQLPath,
		bytes.NewReader(grpcFrame(t, "INSERT INTO todos (title) VALUES ('x')")),
	)
	insertResponse := httptest.NewRecorder()
	proxy.ServeHTTP(insertResponse, insertRequest)
	if insertResponse.Code != http.StatusOK || insertResponse.Header().Get("grpc-status") != "14" {
		t.Fatalf("insert response = %d grpc-status %q, want HTTP 200 grpc-status 14",
			insertResponse.Code, insertResponse.Header().Get("grpc-status"))
	}

	selectRequest := httptest.NewRequest(
		http.MethodPost,
		executeSQLPath,
		bytes.NewReader(grpcFrame(t, "SELECT * FROM todos")),
	)
	selectResponse := httptest.NewRecorder()
	proxy.ServeHTTP(selectResponse, selectRequest)
	if selectResponse.Code != http.StatusNoContent {
		t.Fatalf("select response = %d, want upstream HTTP 204", selectResponse.Code)
	}
}

func TestControlEndpointEnablesAndDisablesTodoInsertFailure(t *testing.T) {
	proxy := &spannerProxy{}
	handler := newControlHandler(proxy)

	enableRequest := httptest.NewRequest(http.MethodPost, "/faults/insert-into-todos", nil)
	enableResponse := httptest.NewRecorder()
	handler.ServeHTTP(enableResponse, enableRequest)
	if enableResponse.Code != http.StatusNoContent || !proxy.failTodoInsert.Load() {
		t.Fatalf("enable response = %d, fault enabled = %t", enableResponse.Code, proxy.failTodoInsert.Load())
	}

	disableRequest := httptest.NewRequest(http.MethodDelete, "/faults/insert-into-todos", nil)
	disableResponse := httptest.NewRecorder()
	handler.ServeHTTP(disableResponse, disableRequest)
	if disableResponse.Code != http.StatusNoContent || proxy.failTodoInsert.Load() {
		t.Fatalf("disable response = %d, fault enabled = %t", disableResponse.Code, proxy.failTodoInsert.Load())
	}
}

func grpcFrame(t *testing.T, sql string) []byte {
	t.Helper()
	message, err := proto.Marshal(&spannerpb.ExecuteSqlRequest{Sql: sql})
	if err != nil {
		t.Fatal(err)
	}
	frame := make([]byte, 5+len(message))
	binary.BigEndian.PutUint32(frame[1:5], uint32(len(message)))
	copy(frame[5:], message)
	return frame
}
