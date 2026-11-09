# プロジェクト共通コーディング規約・エージェントガイドライン

本リポジトリ（Kotlin Spring Boot CRUD API）で作業する AI コーディングエージェント（Antigravity, GitHub Copilot）および開発者向けの共通ガイドラインです。

## 1. 技術スタック
- **言語**: Kotlin 2.2 (Java 21)
- **フレームワーク**: Spring Boot 4.0 (Web, Data JPA, Validation)
- **データベース**: Cloud Spanner (ローカル開発・テストではCloud Spanner Emulator)
- **静的解析・セキュリティ**: Detekt 1.23, JetBrains Qodana (Community JVM), Aqua Security Trivy (脆弱性・ライセンス・CycloneDX SBOM生成), OpenSSF Scorecard (依存OSS健全性・持続性・サプライチェーン評価・JSON保管), Socket Security, Gradle Dependency Verification (チェックサム改ざん検証)
- **テスト**: JUnit 5, MockMvc, REST Assured, Kotest, swagger-parser
- **API仕様・品質検証**: OpenAPI 3.0.3 ([docs/openapi.yaml](docs/openapi.yaml)), OpenAPI Arazzo 1.0.1 ([docs/workflows/todo-workflows.arazzo.yaml](docs/workflows/todo-workflows.arazzo.yaml)), Stoplight Spectral 6.16, Redocly CLI, OpenAPI Generator Validate

## 2. コーディングルール
1. **コードスタイル & 静的解析**:
   - Detekt のルールに準拠すること。
   - `./gradlew detekt` で静的解析を実施し、指摘事項を解消すること（自動修正可能ルールは自動修正されます）。
   - テストコードにおいても関数の長さ（`LongMethod`: 60行以内）、引数の数（`LongParameterList`: 6個以内）、マジックナンバーの排除（定数化）、メソッドの return 数（`ReturnCount`: 2以下）を意識した実装を行うこと。
2. **OpenAPI仕様ファースト / 日英運用 & 外部公開安全性 & 品質検証**:
   - 外部公開用API（正本・英語SSOT）は [`docs/openapi.yaml`](docs/openapi.yaml) に定義します。外部配布物に内部・管理者向け情報が漏洩しないよう、安全性の原則（Allowlist）に従い外部公開可能なAPIのみをベース仕様とします。
   - 内部専用・管理者向けエンドポイント（Admin API等）は OpenAPI Overlay 仕様（[`docs/overlays/admin.yaml`](docs/overlays/admin.yaml)）で拡張・合成し、内部完全版仕様（`docs/dist/openapi.admin.yaml`）を生成します。
   - 日本語版仕様（`docs/dist/openapi.ja.yaml`）は [`docs/overlays/ja.yaml`](docs/overlays/ja.yaml) により自動生成されます。
   - `./gradlew lintOpenApi` または `./gradlew bundleOpenApi` を実行することで各仕様書が自動生成・Spectral検証されます。
   - API の呼び出し順序や一連のユースケース（ユーザー登録、ログイン、Todo作成・更新、管理者監査など）は Arazzo ワークフロー仕様（[`docs/workflows/todo-workflows.arazzo.yaml`](docs/workflows/todo-workflows.arazzo.yaml)）に定義し、`npm run lint:arazzo` または `./gradlew lintOpenApi` で検証します。
   - `./gradlew lintOpenApi` で Spectral による全仕様（ベース・管理者向け・日英仕様）および Redocly による Arazzo ワークフローの静的検証をパスすること。
   - 統合テスト（`integrationTest`）で OpenAPI 仕様（内部完全版）との整合性、および**定義されたレスポンスと制約（`pattern`, `enum`, `minimum` 等）の 100% テストカバレッジ**が自動検証されます。
3. **依存関係の管理とロック**:
   - Gradle Version Catalog (`gradle/libs.versions.toml`) および依存関係ロック（`dependencyLocking { lockAllConfigurations() }`）を採用しています。
   - 依存関係を追加または削除した際は、必ず `./gradlew dependencies --write-locks` を実行して `gradle.lockfile` を同期すること。
   - Dependency Analysis プラグインが有効化されているため、コードから直接使われていない依存関係（Unused dependencies）は宣言しないこと。
4. **エラーハンドリング**:
   - 不正なリクエストは `400 Bad Request`、未認証アクセスは `401 Unauthorized`、重複時は `409 Conflict`、リソース不在時は `404 Not Found` を返すこと。
5. **トークン消費・コンテキスト効率化 (ccusage 対策)**:
   - AI コーディングエージェントのコンテキスト窓圧迫やトークン浪費を防ぐため、実装中の検証は `./gradlew --offline detekt` や `--tests "<クラス名>"` でピンポイントに実施し、全検証（`./gradlew check`）はコミット直前に集約すること。
   - コマンド実行時のログ過多を抑止し（Gradle の `testLogging` で失敗時情報のみ表示）、不要なファイル全読込を避けて範囲指定で読み込むこと。
   - `npm run usage`（`ccusage -O`）を用いてセッションや日別のトークン使用量を可視化・確認すること。

## 3. テストと検証
変更完了後は必ず次のコマンドで全検証を実行し、エラーがないことを確認すること:
```bash
./gradlew check
```
※ `check` タスクにより Detekt、ビルド健全性（Dependency Analysis: `buildHealth`）、OpenAPIリンティング（Spectral: `lintOpenApi` / `openApiValidate`）、ユニットテスト、結合テストが一括実行されます。
※ エミュレータ接続を伴うテスト実行時は、ポート 9010 (Spanner) および 8085 (Pub/Sub) へのソケット接続が必要です。
※ ステージングやQA環境等のテスト環境における検証手順・チェックリスト・自動スモークテストは [docs/testing/test-environment-specification.md](docs/testing/test-environment-specification.md) に定義されています。

## 4. スキル・手順書（Runbook）の参照
特定のタスクを実施する際は、[.github/skills/](.github/skills/) 配下の各手順書（`SKILL.md`）を参照してください:
- テストと検証: [.github/skills/run-tests/SKILL.md](.github/skills/run-tests/SKILL.md)
- APIの変更・追加: [.github/skills/update-api/SKILL.md](.github/skills/update-api/SKILL.md)
- アプリケーション起動と動作確認: [.github/skills/run-app/SKILL.md](.github/skills/run-app/SKILL.md)
