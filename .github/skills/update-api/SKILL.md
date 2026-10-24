---
name: update-api
description: APIエンドポイントの新規追加、変更、削除を行う際に、OpenAPI仕様書 (docs/openapi.yaml) と実装コードを同期して整合性を維持する手順
---

# API仕様更新および実装手順 (Update API)

このスキルは、REST APIの変更や新規追加を行う際の手順を提供します。
本プロジェクトでは OpenAPI 仕様と実装が統合テストで厳密に照合されるため、手順に従って作業してください。

## 作業手順

### Step 1: OpenAPI 仕様 (`docs/openapi.yaml`) および日本語オーバーレイ (`docs/overlays/ja.yaml`) の更新
1. `docs/openapi.yaml` を開き、対象のエンドポイント（パス、HTTPメソッド、リクエストボディ、レスポンスコード、スキーマ）を英語（SSOT）で定義・更新します。
2. 日本語の説明・要約を提供する場合は、OpenAPI Overlay 仕様に基づき `docs/overlays/ja.yaml` に対応するターゲットパス（JSONPath）と日本語翻訳（`update`）を追加します。
3. `./gradlew lintOpenApi` を実行し、`bundleOpenApiJa` による日本語版仕様（`docs/dist/openapi.ja.yaml`）の生成および Spectral 静的検証（日英両仕様）をパスすることを確認します。

### Step 2: 実装コードの更新
1. **DTO / Request / Response モデル**:
   - `src/main/kotlin/com/example/` 配下の該当モデルクラスを更新します。
   - Bean Validation アノテーション（`@field:NotBlank` など）が仕様と合致しているか確認します。
2. **Controller**:
   - リクエストマッピング、引数、ステータスコードを OpenAPI 仕様と一致させます。
3. **Service / Repository**:
   - 必要に応じてビジネスロジックやデータ永続化を実装します。

### Step 3: テストコードの追加・更新
1. **コントローラーテスト (MockMvc)**:
   - `src/test/kotlin/` にユニットテストを追加し、正常系・異常系（400, 401, 404など）を検証します。
2. **結合テスト (REST Assured + OpenAPI Validator & Coverage Filter)**:
   - `src/integrationTest/kotlin/` にテストケースを追加します。
   - 統合テスト実行時に、`swagger-parser` と Kotest (`OpenApiValidationFilter`) がレスポンスを `docs/openapi.yaml` と自動照合します。
   - **OpenAPI 制約カバレッジ (100% 網羅) の重要要件**:
     - `OpenApiCoverageFilter` により、OpenAPI で定義されたすべてのレスポンスステータスコードだけでなく、リクエストボディに定義されたバリデーション制約（`required`, `pattern`, `enum`, `minimum` 等）を**100% テストで網羅することが強制**されています。
     - スキーマに `pattern`（例: `pattern: '.*\S.*'`）や `enum`、`minimum` 等を追加・変更した場合、必ず `src/integrationTest/kotlin/com/example/todo/TodoApiTestCases.kt` の `validationErrorTestCases()`（および `acceptanceCriteria`）に該当制約の違反リクエスト（400 Bad Request を期待するテストケース）を追加してください。
     - 制約違反テストが不足している場合、`verifyAllOpenApiConstraintsCovered()` によりテストが失敗します。

### Step 4: 検証
```bash
./gradlew check
```
すべてのテストと Detekt 静的解析がパスすることを確認します。
