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
  ```
- **結合テストのみ (REST Assured + OpenAPI Validator)**:
  ```bash
  ./gradlew --offline integrationTest
  ```
- **依存関係の健全性検証のみ (Dependency Analysis)**:
  ```bash
  ./gradlew buildHealth
  ```
  ※ 宣言されているが使用されていない依存関係（Unused dependencies）がないかを検証します。

## エラー発生時のトラブルシューティング

1. **Detekt の違反が発生した場合**:
   - Detekt は `autoCorrect = true` に設定されているため、実行時に自動修正される項目があります。
   - 自動修正されない命名規則や複雑度警告（`LongMethod`: 60行超過、`LongParameterList`: 6引数超過、`MagicNumber`、`ReturnCount`）は、コードやテストをリファクタリングして解消してください。

2. **統合テストで OpenAPI 検証エラーが発生した場合**:
   - Kotest (`matchOpenApiSchema` / `OpenApiValidationFilter`) のスキーマ検証エラーメッセージを確認してください。
   - レスポンスのフィールド欠落、余分なフィールド、型不整合、ステータスコード不一致などが原因です。
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
