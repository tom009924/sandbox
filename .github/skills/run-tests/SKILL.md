---
name: run-tests
description: プロジェクトのユニットテスト、静的解析 (Detekt)、および OpenAPI 仕様照合を含む統合テストを実行・デバッグする手順
---

# テストと品質検証手順 (Run Tests & Verification)

このスキルは、プロジェクトのテスト実行、静的コード解析、および検証エラーの解決手順を提供します。

## 主な実行コマンド

### 1. 全検証の実行（推奨）
最初にエミュレータ（Spanner / Pub/Sub）をバックグラウンド起動し、続いて静的解析 (Detekt) → ユニットテスト → 統合テスト (OpenAPI 検証含む) を一括実行します。
※ `build.gradle.kts` で `SPANNER_EMULATOR_HOST=localhost:9010` と `PUBSUB_EMULATOR_HOST=localhost:8085` がデフォルト設定されているため、個別の `export` は不要です。

1. エミュレータの起動:
   ```bash
   docker compose up -d
   ```
2. 全検証の実行（依存関係キャッシュ済みなら `--offline` で高速実行可能）:
   ```bash
   ./gradlew --offline check
   ```

### 2. 個別タスクの実行

- **静的コード解析のみ (Detekt・エミュレータ不要・サンドボックス内で実行可能)**:
  ```bash
  ./gradlew --offline detekt
  ```
  ※ 自動修正可能なスタイル違反は自動で修正されます。
- **OpenAPI 仕様の静的解析のみ (Spectral・エミュレータ不要)**:
  ```bash
  ./gradlew lintOpenApi
  ```
  ※ OpenAPI 仕様の構文、命名規則（kebab-case/camelCase）、必須メタデータなどを検証します。
- **ユニットテストのみ (MockMvc)**:
  ```bash
  ./gradlew --offline test
  # 特定のテストクラスに絞って高速実行（トークン・実行時間節約）:
  ./gradlew --offline test --tests "com.example.todo.application.TodoServiceTest"
  ```
- **結合テストのみ (REST Assured + OpenAPI Validator)**:
  ```bash
  ./gradlew --offline integrationTest
  ```
- **OpenAPI スペックカバレッジの計測と標準出力**:
  ```bash
  npm run coverage:spec
  # または Gradle 直接実行:
  ./gradlew --offline integrationTest --tests "com.example.todo.TodoApiIntegrationTest"
  ```
  ※ 一時ファイル作成を行わず、OpenAPI 仕様に対するエンドポイント、レスポンスステータスコード、リクエストパラメータ、バリデーション制約の網羅率をインメモリで集計してターミナル（標準出力）に直接出力します。
- **トークン・セッション使用量・コストの計測 (ccusage)**:
  ```bash
  npm run usage
  # または日別・セッション別詳細:
  npm run usage:daily
  npm run usage:session
  ```
  ※ オフラインモード（`-O`）で即座に実行され、エージェント環境下でもネットワーク待機なしでトークン消費量やコストを確認できます。
- **Arazzo ワークフローテスト（E2E / スモーク・ローカルまたは実環境）**:
  ```bash
  # 起動中のアプリケーション（localhost:8080）に対して実行（一意のユーザー名で自己完結）
  npm run test:arazzo

  # 実環境（ステージング等）に対して実行
  SERVER_URL=https://staging-api.example.com npm run test:arazzo

  # 任意のアカウント名を指定して実行する場合
  ARAZZO_USERNAME=custom-user npm run test:arazzo
  ```
  ※ Redocly Respect を利用し、`docs/workflows/todo-workflows.arazzo.yaml` で定義された一連の業務シナリオ（登録〜設定〜TODO CRUD、管理者監査）を自動実行します。未指定時は実行ごとに一意なユーザー名（`arazzo-user-<timestamp>`）が自動採番され、アカウント衝突（409）を防ぎます。全体のテスト方針は [`docs/testing/test-strategy.md`](../../docs/testing/test-strategy.md) を参照してください。
- **依存関係の健全性検証のみ (Dependency Analysis)**:
  ```bash
  ./gradlew buildHealth
  ```
  ※ 宣言されているが使用されていない依存関係（Unused dependencies）がないかを検証します。
- **JetBrains Qodana によるコード品質検証 (CI / Docker)**:
  ```bash
  docker run --rm -v "$(pwd):/data/project" jetbrains/qodana-jvm-community:latest
  ```
  ※ GitHub Actions（`.github/workflows/qodana.yml`）で自動実行されます。ローカルで検証する場合は Docker を使用して実行できます。
- **Aqua Security Trivy によるセキュリティ・脆弱性検証 (npm / Docker)**:
  ```bash
  npm run trivy:scan
  # またはキャッシュマウント付きDockerコマンドで高速実行:
  docker run --rm -v /tmp/trivy-cache:/root/.cache/ -v "$(pwd):/project" -w /project aquasec/trivy:latest fs .
  ```
  ※ トリアージ済みの脆弱性一覧を確認する場合は `npm run trivy:suppressed` を実行します。GitHub Actions（`.github/workflows/trivy.yml`）で自動実行され、新規の CRITICAL / HIGH 脆弱性が検出されるとビルドが失敗（exit-code: 1）します。

## エラー発生時のトラブルシューティング

1. **Detekt の違反が発生した場合**:
   - Detekt は `autoCorrect = true` に設定されているため、実行時に自動修正される項目があります。
   - 自動修正されない命名規則や複雑度警告（`LongMethod`: 60行超過、`LongParameterList`: 6引数超過、`MagicNumber`、`ReturnCount`）は、コードやテストをリファクタリングして解消してください。

2. **統合テストで OpenAPI 検証エラーが発生した場合**:
   - `OpenApiValidationFilter` のリクエスト/レスポンススキーマ検証エラーメッセージを確認してください。
   - リクエスト/レスポンスのフィールド欠落、余分なフィールド、型不整合、ステータスコード不一致などが原因です。
   - `verifyAllOpenApiConstraintsCovered()` で失敗した場合は、OpenAPI 仕様に追加された `pattern` や `enum`、`minimum` 等の制約に対する異常系（400）テストが `TodoApiTestCases.kt` に不足しています。対応するテストケースを追加してください。

3. **Cloud Spanner Emulator のスキーマエラー (`Cannot add NOT NULL column`)**:
   - JPA Entity に新規カラム（特に `NOT NULL`）を追加した場合、Spanner の制約により `ddl-auto=update` でのスキーマ変更が失敗します。
   - エミュレータコンテナを再起動してインメモリのデータベースを初期化してください:
     ```bash
     docker restart kotlin-devcontainer-spanner-emulator-1
     ```
   - ※ 再起動直後はポート 9010 への接続受付が完了するまで数秒待ってからテストを実行してください。

4. **Gradle キャッシュロックによるタイムアウト (`journal-1.lock`)**:
   - Gradle プロセスが強制終了・中断された場合、`Timeout waiting to lock journal cache (~/.gradle/caches/journal-1/journal-1.lock)` エラーが発生することがあります。
   - 実行中の Gradle プロセスがないことを確認（`ps aux | grep gradle`）した上で、残存するロックファイルを削除してください:
     ```bash
     find ~/.gradle/caches/ -name "*.lock" -delete
     ```

5. **Dependency Analysis (`buildHealth`) の違反**:
   - 宣言されているがコードで参照されていない依存関係があると `:buildHealth` が失敗します。
   - `build.gradle.kts` および `gradle/libs.versions.toml` から不要な依存を削除し、`dependencyLocking` を更新してください:
     ```bash
     ./gradlew dependencies --write-locks
     ```

6. **メモリ不足エラーが発生した場合**:
   - `build.gradle.kts` で並列ワーカー数やヒープサイズが制限されています (`maxHeapSize = "256m"`, `maxParallelForks = 1`)。
   - 必要に応じて `--no-daemon` オプションをつけて実行してください。

7. **エージェント環境（サンドボックス）でのネットワーク / ポート接続**:
   - 単体テスト（MockMvc）や結合テスト（REST Assured）は、ローカルエミュレータ（Spanner: 9010, Pub/Sub: 8085）やランダム HTTP ポートへのソケット接続を伴うため、エージェント環境ではネットワーク許可（`BypassSandbox: true`）が必要です。
   - Detekt、Spectral リンティング（`lintOpenApi`）、OpenAPI バリデーション（`openApiValidate`）は標準サンドボックス内で実行可能です。
