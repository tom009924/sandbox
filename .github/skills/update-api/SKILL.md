---
name: update-api
description: APIエンドポイントの新規追加、変更、削除を行う際に、OpenAPI仕様書 (docs/openapi.yaml) と実装コードを同期して整合性を維持する手順
---

# API仕様更新および実装手順 (Update API)

このスキルは、REST APIの変更や新規追加を行う際の手順を提供します。
本プロジェクトでは OpenAPI 仕様と実装が統合テストで厳密に照合されるため、手順に従って作業してください。

## 作業手順

### Step 1: OpenAPI 仕様 (`docs/openapi.yaml`) の更新
1. `docs/openapi.yaml` を開き、対象のエンドポイント（パス、HTTPメソッド、リクエストボディ、レスポンスコード、スキーマ）を定義・更新します。
2. スキーマ（`components/schemas`）に必要なフィールド、型、バリデーション（`required` や `pattern` 等）を正確に記載します。

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
   - `src/test/kotlin/` にユニットテストを追加し、正常系・異常系（400, 404など）を検証します。
2. **結合テスト (REST Assured + OpenAPI Validator)**:
   - `src/integrationTest/kotlin/` にテストケースを追加します。
   - 統合テスト実行時に、`swagger-parser` と Kotest (`OpenApiValidationFilter`) がリクエスト・レスポンスを `docs/openapi.yaml` と自動照合します。

### Step 4: 検証
```bash
./gradlew check
```
すべてのテストと Detekt 静的解析がパスすることを確認します。
