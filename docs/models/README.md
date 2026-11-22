# モデルベースドテスト仕様 (`docs/models`)

本ディレクトリでは、OpenAPI 仕様 (`docs/openapi.yaml`) の `operationId` と対応付けた標準仕様モデルとモデルベースドテスト（MBT）資産を管理しています。

## OMG DMN 1.3 ディシジョンモデル

- [`todo-decisions.dmn`](todo-decisions.dmn): DB 状態とリクエスト入力に基づく業務ルール判定の OMG DMN 1.3 仕様
- [`DMN.md`](DMN.md): DMN モデルから自動生成された Decision Requirements Diagram (Mermaid) およびディシジョンテーブル一覧（`npm run generate:dmn-diagrams`）

### コマンド

```bash
# DMN モデル検証 & 図・テーブルの整合性チェック（lint:openapi:check に組み込み済み）
npm run lint:dmn
npm run generate:dmn-diagrams

# DMN から todo-service 用 Kotlin ドメイン判定モデルを生成
npm run generate:dmn-kotlin

# DMN モデルベース統合テストを実行
./gradlew :services:todo-service:propertyTest --tests "com.example.todo.mbt.DmnTodoModelBasedIntegrationTest"
```
