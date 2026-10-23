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

## エラー発生時のトラブルシューティング

1. **Detekt の違反が発生した場合**:
   - Detekt は `autoCorrect = true` に設定されているため、実行時に自動修正される項目があります。
   - 自動修正されない命名規則や複雑度警告は、指摘された行を修正して再度 `./gradlew detekt` を実行してください。

2. **統合テストで OpenAPI 検証エラーが発生した場合**:
   - Kotest (`matchOpenApiSchema` / `OpenApiValidationFilter`) のスキーマ検証エラーメッセージを確認してください。
   - レスポンスのフィールド欠落、余分なフィールド、型不整合、ステータスコード不一致などが原因です。
   - 実装コードと `docs/openapi.yaml` のどちらが正解か確認し、両者を同期させてください。

3. **メモリ不足エラーが発生した場合**:
   - `build.gradle.kts` で並列ワーカー数やヒープサイズが制限されています (`maxHeapSize = "256m"`, `maxParallelForks = 1`)。
   - 必要に応じて `--no-daemon` オプションをつけて実行してください。
