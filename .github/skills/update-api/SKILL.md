---
name: update-api
description: APIエンドポイントの新規追加、変更、削除を行う際に、OpenAPI仕様書 (docs/openapi.yaml) と実装コードを同期して整合性を維持する手順
---

# API仕様更新および実装手順 (Update API)

このスキルは、REST APIの変更や新規追加を行う際の手順を提供します。
本プロジェクトでは OpenAPI 仕様と実装が統合テストで厳密に照合されるため、手順に従って作業してください。

## 作業手順

### Step 1: OpenAPI 仕様 (`docs/openapi.yaml`) およびオーバーレイ (`docs/overlays/`) の更新
1. 外部公開用APIを変更・追加する場合は、`docs/openapi.yaml`（外部向けベース・英語SSOT）に定義・更新します。
2. 内部専用・管理者向けエンドポイント（Admin API等）を追加・更新する場合は、安全性を確保するため外部向けベースではなく OpenAPI Overlay 仕様（`docs/overlays/admin.yaml`）に拡張定義（`update`）を追加し、内部完全版（`docs/dist/openapi.admin.yaml`）を生成するようにします。
3. 日本語の説明・要約を提供する場合は、OpenAPI Overlay 仕様に基づき `docs/overlays/ja.yaml` に対応するターゲットパス（JSONPath）と日本語翻訳（`update`）を追加します。
4. 必要に応じて一連のユースケース（ユーザーシナリオ）を定義した Arazzo ワークフロー仕様（`docs/workflows/todo-workflows.arazzo.yaml`）も更新します。
5. `./gradlew lintOpenApi` を実行し、`bundleOpenApi` による外部向け仕様（`docs/dist/openapi.yaml` / `docs/dist/openapi.ja.yaml`）および内部仕様（`docs/dist/openapi.admin.yaml`）の生成、Spectral による静的検証、ならびに Redocly による Arazzo ワークフローの静的検証をパスすることを確認します。

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
   - 統合テスト実行時に、`OpenApiValidationFilter` が REST-assured のリクエストおよびレスポンスを `docs/openapi.yaml` と自動照合します。
   - **OpenAPI 制約カバレッジ (100% 網羅) の重要要件**:
     - `OpenApiCoverageFilter` により、OpenAPI で定義されたすべてのレスポンスステータスコードだけでなく、リクエストボディに定義されたバリデーション制約（`required`, `pattern`, `enum`, `minimum` 等）を**100% テストで網羅することが強制**されています。
     - スキーマに `pattern`（例: `pattern: '.*\S.*'`）や `enum`、`minimum` 等を追加・変更した場合、必ず `src/integrationTest/kotlin/com/example/todo/TodoApiTestCases.kt` の `validationErrorTestCases()`（および `acceptanceCriteria`）に該当制約の違反リクエスト（400 Bad Request を期待するテストケース）を追加してください。
     - 制約違反テストが不足している場合、`verifyAllOpenApiConstraintsCovered()` によりテストが失敗します。

### Step 4: 検証
```bash
./gradlew check
```
すべてのテストと Detekt 静的解析がパスすることを確認します。
※ アプリケーション起動後（`./gradlew bootRun`）に一連のユースケースが正常に動作するかをクイック検証する場合は、`npm run test:arazzo` を実行してください。テスト環境へのデプロイ後の検証手順は [`docs/testing/test-environment-specification.md`](../../docs/testing/test-environment-specification.md) を参照してください。
