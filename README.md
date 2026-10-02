# Kotlin Spring Boot CRUD API

Kotlin + Spring Bootで作成した、Todo管理用のREST APIです。データはH2のインメモリデータベースに保存されます。

## 起動

Java 21が必要です。プロジェクトのルートで次を実行してください。

```bash
./gradlew bootRun
```

APIのベースURLは `http://localhost:8080/api/todos` です。

## API

| Method | Path | 説明 |
| --- | --- | --- |
| `GET` | `/api/todos` | Todo一覧 |
| `GET` | `/api/todos/{id}` | Todo取得 |
| `POST` | `/api/todos` | Todo作成 |
| `PUT` | `/api/todos/{id}` | Todo更新 |
| `DELETE` | `/api/todos/{id}` | Todo削除 |

作成リクエスト:

```json
{"title":"Buy milk"}
```

更新リクエスト:

```json
{"title":"Buy milk","completed":true}
```

存在しないIDは `404 Not Found`、空のタイトルは `400 Bad Request` を返します。
H2コンソールは `http://localhost:8080/h2-console` で利用できます（JDBC URL: `jdbc:h2:mem:todos`）。

## テスト

```bash
./gradlew test
```
