# Kotlin Spring Boot CRUD API

Kotlin + Spring Bootで作成した、Todo管理用のREST APIです。ローカル開発とテストではCloud Spanner EmulatorおよびCloud Pub/Sub Emulatorを使用します。

## 起動

Java 21が必要です。プロジェクトのルートで次を実行してください。

```bash
docker compose up -d spanner-emulator pubsub-emulator
export SPANNER_EMULATOR_HOST=localhost:9010
export PUBSUB_EMULATOR_HOST=localhost:8085
./gradlew bootRun
```

Spanner Emulatorはデータベースとインスタンスを自動作成します。停止する場合は `docker compose down` を実行してください。Emulatorのデータは停止すると消去されます。
実Spannerに接続する場合は `SPANNER_JDBC_URL` を設定し、Spanner JDBC Driverが利用するGoogle Cloud認証を構成してください。
実Pub/Subに接続する場合は `PUBSUB_PROJECT_ID` や `PUBSUB_TOPIC_ID` を設定し、Google Cloud認証を構成してください。

Dev ContainerにはGitHub CLI (`gh`) が含まれます。設定を反映するにはコンテナを再ビルドし、GitHub CLIを使う場合はコンテナ内で認証してください。
Docker-in-DockerとDocker Compose v2も使えます。Dev Containerを再ビルドした後、`docker version` と `docker compose version` で確認してください。

```bash
gh auth login
gh auth status
```

GradleのビルドキャッシュとKotlinのインクリメンタルコンパイルを有効にし、メモリ使用量を抑えるためGradleの並列ワーカー数を1に制限しています。

APIのベースURLは `http://localhost:8080/api/todos` です。
OpenAPI仕様は [`docs/openapi.yaml`](docs/openapi.yaml) にあります。

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
{"title":"Buy milk","description":"Buy before the weekend","dueDate":"2025-04-18","priority":"HIGH","estimatedMinutes":30}
```

Todo作成では `Idempotency-Key` ヘッダーが必須です。キーは1〜255文字で、大文字小文字を区別します。同じキーと同じリクエストを24時間以内に再送すると最初の `201` レスポンスを再返却し、同じキーを異なるリクエストに使うと `409 Conflict` を返します。
Todoの`id`にはUUIDを使用します。作成レスポンスに含まれる`id`を、取得・更新・削除の各エンドポイントで使用してください。
`description`と`dueDate`は省略可能です。`dueDate`は`YYYY-MM-DD`形式で、`priority`は`LOW`・`MEDIUM`・`HIGH`のいずれかです。優先度を省略すると`MEDIUM`になります。
`estimatedMinutes`は任意の0以上の整数です。所要時間が未定の場合は省略できます。

## アーキテクチャ

Todo機能は依存関係を内側へ向けたレイヤード構成です。

- `presentation`: REST Controller、HTTP DTO、例外からHTTPレスポンスへの変換
- `application`: Todoのユースケースと業務処理
- `domain`: Todoモデル、Repositoryポート、ドメイン例外
- `infrastructure`: Spring Data JPA、永続化Entity、Repositoryアダプター

ControllerはApplication Serviceを呼び出し、Domainが定義するRepositoryポートをInfrastructureが実装します。
この依存方向とDomain/Applicationのフレームワーク非依存はArchUnitのアーキテクチャテストで検証します。

更新リクエスト:

```json
{"title":"Buy milk","completed":true,"description":"Purchased","dueDate":"2025-04-18","priority":"LOW","estimatedMinutes":30}
```

`PUT`ではTodoを置き換えます。`description`、`dueDate`または`estimatedMinutes`を省略すると未設定になり、`priority`を省略すると`MEDIUM`になります。

存在しないIDは `404 Not Found`、空のタイトルは `400 Bad Request` を返します。

## テスト

```bash
./gradlew test
```

MockMvcによるコントローラーテストを実行します。静的解析は次で実行できます。

```bash
./gradlew detekt
```

Detektは自動修正可能なルールを実行時に自動修正します。
OpenAPI仕様書（`docs/openapi.yaml`）の静的解析（Stoplight Spectralによる命名規則や必須メタデータの検証）は次で実行できます。

```bash
./gradlew lintOpenApi
```

MockMvcテストとREST AssuredによるHTTP統合テストはCloud Spanner EmulatorおよびCloud Pub/Sub Emulatorを利用します。テスト実行前にEmulatorを起動し、`SPANNER_EMULATOR_HOST` および `PUBSUB_EMULATOR_HOST` を設定してください。HTTP統合テストでは `swagger-parser` と Kotest を使い、レスポンスを `docs/openapi.yaml` のスキーマと照合します。また、OpenAPI仕様書で定義されたすべてのエンドポイント、レスポンスステータスコード、リクエスト制約がテストされているかも自動検証します。

```bash
docker compose up -d spanner-emulator pubsub-emulator
export SPANNER_EMULATOR_HOST=localhost:9010
export PUBSUB_EMULATOR_HOST=localhost:8085
./gradlew integrationTest
```

`check`を実行するとDetekt、ユニットテスト、インテグレーションテストを順に実行します。

## AI コーディングエージェント設定

本リポジトリでは、Antigravity および GitHub Copilot に対応した共通スキルとルールを一元管理しています。

- **共通スキル (Runbook)**: [`.github/skills/`](.github/skills/)
  - [`run-tests`](.github/skills/run-tests/SKILL.md): テスト・静的解析の実行とトラブルシューティング
  - [`update-api`](.github/skills/update-api/SKILL.md): OpenAPI仕様と実装コードの整合性同期
  - [`run-app`](.github/skills/run-app/SKILL.md): アプリケーションのローカル起動と動作確認
- **共通ガイドライン**: [`.github/rules/guidelines.md`](.github/rules/guidelines.md)
- **エージェント個別設定**:
  - Antigravity: [`.agents/skills.json`](.agents/skills.json)（`.github/skills` を探索先として設定）、[`AGENTS.md`](AGENTS.md)
  - GitHub Copilot: [`.github/copilot-instructions.md`](.github/copilot-instructions.md)
