# Kotlin Spring Boot CRUD API

Kotlin + Spring Bootで作成した、Todo管理用のREST APIです。データはH2のインメモリデータベースに保存されます。

## 起動

Java 21が必要です。プロジェクトのルートで次を実行してください。

```bash
./gradlew bootRun
```

Dev ContainerにはGitHub CLI (`gh`) が含まれます。設定を反映するにはコンテナを再ビルドし、GitHub CLIを使う場合はコンテナ内で認証してください。

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
{"title":"Buy milk","description":"Buy before the weekend","dueDate":"2025-04-18","priority":"HIGH"}
```

Todoの`id`にはUUIDを使用します。作成レスポンスに含まれる`id`を、取得・更新・削除の各エンドポイントで使用してください。
`description`と`dueDate`は省略可能です。`dueDate`は`YYYY-MM-DD`形式で、`priority`は`LOW`・`MEDIUM`・`HIGH`のいずれかです。優先度を省略すると`MEDIUM`になります。

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
{"title":"Buy milk","completed":true,"description":"Purchased","dueDate":"2025-04-18","priority":"LOW"}
```

`PUT`ではTodoを置き換えます。`description`または`dueDate`を省略すると未設定になり、`priority`を省略すると`MEDIUM`になります。

存在しないIDは `404 Not Found`、空のタイトルは `400 Bad Request` を返します。
H2コンソールは `http://localhost:8080/h2-console` で利用できます（JDBC URL: `jdbc:h2:mem:todos`）。

## テスト

```bash
./gradlew test
```

MockMvcによるコントローラーテストを実行します。静的解析は次で実行できます。

```bash
./gradlew detekt
```

Detektは自動修正可能なルールを実行時に自動修正します。

REST AssuredによるHTTP統合テストは、ユニットテストの後に次で実行されます。HTTP統合テストではAtlassian OpenAPI Request Validatorを使い、リクエストとレスポンスを `docs/openapi.yaml` と照合します。また、OpenAPI仕様書で定義されたすべてのエンドポイントおよびレスポンスステータスコード（正常系・異常系）がテストされているかも自動検証します。

```bash
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
