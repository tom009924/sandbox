---
name: run-app
description: Spring Boot アプリケーションをCloud Spanner EmulatorやApigee Emulatorとともにローカル起動し、REST APIを確認する手順
---

# アプリケーション起動と動作確認手順 (Run App)

このスキルは、アプリケーションのローカル起動と動作確認を行う手順を提供します。

## 1. アプリケーションの起動
```bash
docker compose up -d spanner-emulator pubsub-emulator
export SPANNER_EMULATOR_HOST=localhost:9010
export PUBSUB_EMULATOR_HOST=localhost:8085
./gradlew bootRun
```
※ アプリケーションはポート `8080` で起動します。

### Apigee API プロキシ検証（オプション）
Apigee APIプロキシをローカルで開発・検証する場合は、Apigee Emulatorを起動します。
```bash
docker compose up -d apigee-emulator
```
※ ホスト側ポート `8088` (Control/Management API / Proxy)、`8998` (Traffic) で待機します。VS Codeの Cloud Code 拡張機能（`GoogleCloudTools.cloudcode`）からプロキシをデプロイして検証可能です。

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

## 3. Arazzo ワークフローによるシナリオ動作確認

Arazzo 仕様（`docs/workflows/todo-workflows.arazzo.yaml`）を利用して、ユーザー登録から Todo の作成・更新・削除、および管理者監査までの一連のシナリオを自動実行して確認できます。

```bash
# 起動中のアプリケーション（http://localhost:8080）に対して Arazzo ワークフローを実行
npm run test:arazzo
```
※ 任意のサーバーURLやカスタム入力値（ユーザー名等）を指定する場合:
```bash
npx redocly respect docs/workflows/todo-workflows.arazzo.yaml --server http://localhost:8080 --input username=test-user-01
```

## 4. Emulator の停止
```bash
docker compose down
```
Emulatorはインメモリのため、停止するとデータベースと保存データは消去されます。
