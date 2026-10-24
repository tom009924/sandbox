---
name: run-app
description: Spring Boot アプリケーションをCloud Spanner Emulatorとともにローカル起動し、REST APIを確認する手順
---

# アプリケーション起動と動作確認手順 (Run App)

このスキルは、アプリケーションのローカル起動と動作確認を行う手順を提供します。

## 1. アプリケーションの起動
```bash
docker compose up -d spanner-emulator
export SPANNER_EMULATOR_HOST=localhost:9010
./gradlew bootRun
```
※ アプリケーションはポート `8080` で起動します。

## 2. API動作確認 (cURL コマンド例)

Todo APIのエンドポイント（`/api/todos`）はJWT認証が必須です。あらかじめユーザー登録とログインを行ってJWTトークンを取得してください。

### ユーザー登録
```bash
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"password123"}'
```

### ログイン（トークン取得）
```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"password123"}' | jq -r .token)
echo "JWT Token: $TOKEN"
```

### Todo一覧取得
```bash
curl -X GET http://localhost:8080/api/todos \
  -H "Authorization: Bearer $TOKEN"
```

### Todo新規作成
```bash
curl -X POST http://localhost:8080/api/todos \
  -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: create-todo-1" \
  -H "Content-Type: application/json" \
  -d '{"title":"Buy milk"}'
```
Todo作成では一意な `Idempotency-Key` が必須です。同じキー・同じリクエストは24時間以内なら最初の作成レスポンスを再返却し、同じキーで異なるリクエストを送ると `409 Conflict` を返します。

### Todo更新
`{id}`には作成レスポンスで返されたUUIDを指定します。
```bash
curl -X PUT http://localhost:8080/api/todos/550e8400-e29b-41d4-a716-446655440000 \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"title":"Buy milk","completed":true}'
```

### Todo削除
```bash
curl -X DELETE http://localhost:8080/api/todos/550e8400-e29b-41d4-a716-446655440000 \
  -H "Authorization: Bearer $TOKEN"
```

## 3. Emulator の停止
```bash
docker compose down
```
Emulatorはインメモリのため、停止するとデータベースと保存データは消去されます。
