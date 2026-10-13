---
name: run-app
description: Spring Boot アプリケーションをCloud Spanner Emulatorとともにローカル起動し、REST APIを確認する手順
---

# アプリケーション起動と動作確認手順 (Run App)

このスキルは、アプリケーションのローカル起動と動作確認を行う手順を提供します。

## 1. アプリケーションの起動
```bash
docker compose up -d
export SPANNER_EMULATOR_HOST=localhost:8666
./gradlew bootRun
```
※ アプリケーションはポート `8080` で起動します。

## 2. API動作確認 (cURL コマンド例)

### Todo一覧取得
```bash
curl -X GET http://localhost:8080/api/todos
```

### Todo新規作成
```bash
curl -X POST http://localhost:8080/api/todos \
  -H "Content-Type: application/json" \
  -d '{"title":"Buy milk"}'
```

### Todo更新
`{id}`には作成レスポンスで返されたUUIDを指定します。
```bash
curl -X PUT http://localhost:8080/api/todos/550e8400-e29b-41d4-a716-446655440000 \
  -H "Content-Type: application/json" \
  -d '{"title":"Buy milk","completed":true}'
```

### Todo削除
```bash
curl -X DELETE http://localhost:8080/api/todos/550e8400-e29b-41d4-a716-446655440000
```

## 3. Emulator の停止
```bash
docker compose down
```
Emulatorはインメモリのため、停止するとデータベースと保存データは消去されます。
