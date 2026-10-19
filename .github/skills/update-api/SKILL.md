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

### Step 2: 受入テストケース定義と実装前OAS突き合わせ (ATDD)
1. **テーブル駆動テストケース (`TodoApiTestCases.kt`) の追加・更新**:
   - `src/integrationTest/kotlin/com/example/todo/TodoApiTestCases.kt` に、追加・変更したオペレーションやレスポンス、バリデーション制約に対応する `TodoApiTestCase` を定義します。
2. **実装前OAS整合性・網羅性チェックの実行**:
   - 本体実装の前に（エミュレータやサーバー起動不要で）、テストテーブルと `docs/openapi.yaml` の網羅性・リクエストスキーマ整合性を静的に検証します:
     ```bash
     ./gradlew integrationTest -x test --tests com.example.todo.TodoApiTestTableOasTest
     ```

### Step 3: 実装コードとユニットテストの更新
1. **DTO / Request / Response モデル**:
   - `src/main/kotlin/com/example/` 配下の該当モデルクラスを更新します。
   - Bean Validation アノテーション（`@field:NotBlank` など）が仕様と合致しているか確認します。
2. **Controller / Service / Repository**:
   - リクエストマッピング、引数、ステータスコード、ビジネスロジック、永続化を OpenAPI 仕様と一致させます。
3. **ユニットテスト (`src/test/kotlin/`)**:
   - コントローラーやサービス層のユニットテストを追加・更新します。

### Step 4: 全検証
```bash
./gradlew check
```
すべてのテスト（実サーバーに対する統合テスト・OpenAPI双方向検証を含む）と Detekt 静的解析がパスすることを確認します。
