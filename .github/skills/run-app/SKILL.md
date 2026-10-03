---
name: run-app
description: Spring Boot アプリケーションをローカル起動し、REST APIの動作確認や H2 コンソールでのデータ確認を行う手順
---

# アプリケーション起動と動作確認手順 (Run App)

このスキルは、アプリケーションのローカル起動と動作確認を行う手順を提供します。

## 1. アプリケーションの起動
```bash
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
```bash
curl -X PUT http://localhost:8080/api/todos/1 \
  -H "Content-Type: application/json" \
  -d '{"title":"Buy milk","completed":true}'
```

### Todo削除
```bash
curl -X DELETE http://localhost:8080/api/todos/1
```

## 3. H2 データベースコンソール
- **URL**: `http://localhost:8080/h2-console`
- **JDBC URL**: `jdbc:h2:mem:todos`
- **ユーザー名**: `sa` (パスワード空)
ブラウザからテーブル内容やデータを確認する際に利用できます。
