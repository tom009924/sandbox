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
4. 新規・変更した API のユースケースを Arazzo ワークフロー仕様（`docs/workflows/todo-workflows.arazzo.yaml`）に追加・更新し、`npm run generate:diagrams` で `docs/workflows/README.md` を再生成します（`lint:arazzo` により OpenAPI の全 `operationId` カバレッジが強制されます）。
5. `./gradlew lintOpenApi` を実行し、`bundleOpenApi` による `docs/dist/` 生成、Redocly CLI 検証、Arazzo 検証・カバレッジ検査をパスすることを確認します（生成された `docs/dist/` と `docs/workflows/README.md` は必ずコミットしてください）。

### Step 2: 実装コードの更新
1. **DTO / Request / Response モデル**:
   - `src/main/kotlin/com/example/` および `services/api-gateway/src/main/kotlin/com/example/gateway/presentation/` 配下の該当モデルクラスを更新します（必要に応じて `libs/*-proto` の `.proto` 契約も同期）。
   - Bean Validation アノテーション（`@field:NotBlank`, `@field:Size`, `@field:Pattern` など）が OpenAPI 仕様と完全一致しているか確認します。
2. **Controller**:
   - リクエストマッピング、引数、ステータスコードを OpenAPI 仕様と一致させます。
3. **Service / Repository**:
   - 必要に応じてビジネスロジックやデータ永続化を実装します。

### Step 3: テストコードの追加・更新
1. **コントローラーテスト (MockMvc)**:
   - `src/test/kotlin/` にユニットテストを追加し、正常系・異常系（400, 401, 404など）を検証します。
2. **コンテナ統合テスト (REST Assured + OpenAPI Validator & Coverage Filter)**:
   - `integration-tests/src/test/kotlin/com/example/integration/api/ContainerTodoApiIntegrationTest.kt` にテストケースを追加します。
   - 統合テスト実行時に、`swagger-parser` (`OpenApiValidationFilter`) がレスポンスを `docs/dist/openapi.admin.yaml` と自動照合します。
   - **OpenAPI 制約カバレッジ (100% 網羅) の重要要件**:
     - `OpenApiCoverageFilter` により、OpenAPI で定義されたすべてのレスポンスステータスコード・パラメータだけでなく、リクエストボディに定義されたバリデーション制約（`required`, `pattern`, `enum`, `minimum` 等）を**100% テストで網羅することが強制**されています。
     - スキーマに `pattern`（例: `pattern: '.*\S.*'`）や `enum`、`minimum` 等を追加・変更した場合、必ず `integration-tests/src/test/kotlin/com/example/integration/api/TodoApiTestCases.kt` の `validationErrorTestCases()`（および `acceptanceCriteria`）に該当制約の違反リクエスト（400 Bad Request を期待するテストケース）を追加してください。
     - 制約違反テストが不足している場合、`reportAndVerifyOpenApiCoverage()` によりテストが失敗します。

### Step 4: 外部呼び出しシーケンス図と最終検証
1. 新規エンドポイントを追加した場合は、`services/todo-service/src/test/kotlin/com/example/todo/trace/ExternalCallSequenceDiagramGeneratorTest.kt` にトレースシナリオを追加し、`./gradlew generateExternalCallDiagrams` で `docs/architecture/external-call-sequence.md` を更新します（全 OpenAPI エンドポイントの網羅が自動検証されます）。
2. 以下のコマンドですべてのテストと Detekt 静的解析がパスすることを確認します:
```bash
./gradlew check
```
※ アプリケーション起動後（`./gradlew bootRun`）に一連のユースケースが正常に動作するかをクイック検証する場合は、`npm run test:arazzo` を実行してください。テスト環境へのデプロイ後の検証手順は [`docs/testing/test-environment-specification.md`](../../docs/testing/test-environment-specification.md) を参照してください。
