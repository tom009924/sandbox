# Kotlin Spring Boot CRUD API

Kotlin + Spring Bootで作成した、Todo管理用のREST APIです。ローカル開発とテストではCloud Spanner Emulator、Cloud Pub/Sub Emulator、およびApigee Emulatorを使用できます。

## 起動

Java 21が必要です。プロジェクトのルートで次を実行してください。

```bash
docker compose up -d spanner-emulator pubsub-emulator
export SPANNER_EMULATOR_HOST=localhost:9010
export PUBSUB_EMULATOR_HOST=localhost:8085
./gradlew bootRun
```

Apigee APIプロキシのローカル検証を行う場合は、Apigee Emulatorを起動できます。
```bash
docker compose up -d apigee-emulator
npm run deploy:apigee
```
Apigee Emulatorはホスト側ポート `8088`（Management API / デプロイ用）および `8998`（Traffic / プロキシ呼出用）で起動します。リバースプロキシワークスペース（`src/main/apigee`）が同梱されており、`http://localhost:8998/v1/*` へのリクエストが Spring Boot バックエンド（`/api/*`）へルーティングされます（CORS ヘッダー付与対応）。また `src/integrationTest` には Apigee プロキシ経由の疎通・CORS 検証テスト（`ApigeeProxyIntegrationTest`）が含まれています。VS Code環境では Cloud Code 拡張機能（`GoogleCloudTools.cloudcode`）を通じたプロキシデバッグやトレースも可能です。

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

APIのベースURLは `http://localhost:8080/api` です。
OpenAPI仕様は [`docs/openapi.yaml`](docs/openapi.yaml)（日本語版は [`docs/dist/openapi.ja.yaml`](docs/dist/openapi.ja.yaml)）にあります。※ 管理者・内部向けを含む完全版は [`docs/dist/openapi.admin.yaml`](docs/dist/openapi.admin.yaml)（`docs/overlays/admin.yaml` によるオーバーレイ合成）として生成されます。

## API

### 認証 API

| Method | Path | 説明 |
| --- | --- | --- |
| `POST` | `/api/auth/register` | ユーザー新規登録 |
| `POST` | `/api/auth/login` | ログイン（JWTアクセストークン取得） |

登録・ログインリクエスト:

```json
{"username":"alice","password":"password123"}
```

ログイン成功時レスポンス:

```json
{"token":"<JWT_TOKEN>","tokenType":"Bearer","expiresIn":86400}
```

### Todo API (要認証)

Todo APIのエンドポイント（`/api/todos`）の呼び出しにはJWT認証が必要です。HTTPリクエストヘッダーに `Authorization: Bearer <token>` を付与してください。
Todoデータはユーザー単位でスコープ分離されており、ログイン中のユーザーが所有するTodoのみ参照・作成・更新・削除が可能です。未認証リクエストには `401 Unauthorized` を返します。

| Method | Path | 説明 |
| --- | --- | --- |
| `GET` | `/api/todos` | ログインユーザーのTodo一覧（`completed`, `priority` で絞り込み可） |
| `GET` | `/api/todos/{id}` | Todo取得 |
| `POST` | `/api/todos` | Todo作成 |
| `PUT` | `/api/todos/{id}` | Todo更新（全置換） |
| `PATCH` | `/api/todos/{id}` | Todo部分更新（指定フィールドのみ変更） |
| `DELETE` | `/api/todos/{id}` | Todo削除 |

作成リクエスト:

```json
{"title":"Buy milk","description":"Buy before the weekend","dueDate":"2025-04-18","priority":"HIGH","estimatedMinutes":30}
```

Todo作成では `Idempotency-Key` ヘッダーが必須です。キーは1〜255文字で、大文字小文字を区別します。同じキーと同じリクエストを24時間以内に再送すると最初の `201` レスポンスを再返却し、同じキーを異なるリクエストに使うと `409 Conflict` を返します。
Todoの`id`にはUUIDを使用します。作成レスポンスに含まれる`id`を、取得・更新・削除の各エンドポイントで使用してください。
`description`と`dueDate`は省略可能です。`dueDate`は`YYYY-MM-DD`形式で、`priority`は`LOW`・`MEDIUM`・`HIGH`のいずれかです。優先度を省略すると`MEDIUM`になります。
`estimatedMinutes`は任意の0以上の整数です。所要時間が未定の場合は省略できます。

### ユーザー設定 API (要認証)

ログイン中のユーザーの設定やデフォルト値を管理します。

| Method | Path | 説明 |
| --- | --- | --- |
| `GET` | `/api/user/settings` | ユーザー設定取得 |
| `PUT` | `/api/user/settings` | ユーザー設定更新 |

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

部分更新リクエスト (`PATCH`):

```json
{"completed":true,"priority":"HIGH"}
```

`PATCH`では指定した項目のみを更新し、未指定の項目は既存の値を保持します。

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

CI では JetBrains Qodana（設定: [`qodana.yaml`](qodana.yaml)）による静的コード解析および品質チェックが `.github/workflows/qodana.yml` で実行されます。ローカルで実行する場合は Docker を利用できます:

```bash
docker run --rm -v "$(pwd):/data/project" jetbrains/qodana-jvm-community:latest
```

また、Aqua Security Trivy（設定: [`trivy.yaml`](trivy.yaml)、トリアージ定義: [`.trivyignore`](.trivyignore)）によるファイルシステム・依存関係・設定ミスの脆弱性スキャンが `.github/workflows/trivy.yml` で実行されます（CRITICAL/HIGH 脆弱性の検出時はビルドが失敗します）。ローカルで実行する場合は Docker を利用できます:

```bash
npm run trivy:scan
# または直接Dockerで実行（キャッシュ指定により高速化）:
docker run --rm -v /tmp/trivy-cache:/root/.cache/ -v "$(pwd):/project" -w /project aquasec/trivy:latest fs .
```

MockMvcテストとREST AssuredによるHTTP統合テストはCloud Spanner EmulatorおよびCloud Pub/Sub Emulatorを利用します。テスト実行前にEmulatorを起動し、`SPANNER_EMULATOR_HOST` および `PUBSUB_EMULATOR_HOST` を設定してください。HTTP統合テストでは `swagger-parser` と Kotest を使い、レスポンスを `docs/dist/openapi.admin.yaml` のスキーマと照合します。また、OpenAPI仕様書で定義されたすべてのエンドポイント、レスポンスステータスコード、リクエスト制約がテストされているかも自動検証します。

```bash
docker compose up -d spanner-emulator pubsub-emulator
export SPANNER_EMULATOR_HOST=localhost:9010
export PUBSUB_EMULATOR_HOST=localhost:8085
./gradlew integrationTest
```

`check`を実行するとDetekt、ユニットテスト、インテグレーションテストを順に実行します。

### Arazzo ワークフローテスト（E2E / スモーク）

Redocly Respect を利用し、`docs/workflows/todo-workflows.arazzo.yaml` で定義された一連の業務シナリオ（登録、ログイン、設定、TODO CRUD、管理者監査）を自動実行します。

```bash
# 起動中のローカル環境（http://localhost:8080）に対して実行
npm run test:arazzo

# 実環境（ステージング環境等）に対して実行
SERVER_URL=https://staging.api.example.com npm run test:arazzo
```

全体のテスト方針やピラミッド設計は [テスト戦略ドキュメント](docs/testing/test-strategy.md)、デプロイ後の環境検証およびリリース前チェックは [テスト環境仕様書・管理台帳](docs/testing/test-environment-specification.md) を参照してください。

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
