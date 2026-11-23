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

## W3C SCXML 状態遷移モデル

- [`todo-lifecycle.scxml`](todo-lifecycle.scxml): Todo ライフサイクルの W3C SCXML 1.0 ステートチャート仕様
- [`SCXML.md`](SCXML.md): SCXML モデルから自動生成された Mermaid 状態遷移図（`npm run generate:scxml-diagrams`）

### コマンド

```bash
# SCXML モデル検証 & Mermaid 図の整合性チェック（lint:openapi:check に組み込み済み）
npm run lint:scxml
npm run generate:scxml-diagrams

# SCXML から todo-service 用 Kotlin ドメイン状態機械を生成
npm run generate:scxml-kotlin

# SCXML モデルベース統合テストを実行
./gradlew :services:todo-service:propertyTest --tests "com.example.todo.mbt.ScxmlTodoModelBasedIntegrationTest"
```

## Quint (TLA+ 互換) 形式仕様モデル

- [`todo.qnt`](todo.qnt): Todo API の形式仕様モデル（状態変数・OpenAPI `operationId` 対応アクション・不変条件）
- [`traces/todo-model-trace.itf.json`](traces/todo-model-trace.itf.json): モデルから生成された標準 Informal Trace Format (ITF) 実行トレース

### コマンド

```bash
# OpenAPI operationId との整合性チェック（lint:openapi:check に組み込み済み）
npm run lint:quint

# Quint CLI でモデル不変条件の検証および新規 ITF トレースを生成（Quint CLI 導入環境）
quint typecheck docs/models/todo.qnt
quint run docs/models/todo.qnt \
  --invariant=activeTodosWithinLimit \
  --out-itf=docs/models/traces/todo-model-trace.itf.json

# ITF トレースを実 API にリプレイし OpenAPI 契約と不変条件を検証
./gradlew :services:todo-service:propertyTest --tests "com.example.todo.mbt.QuintTodoModelBasedIntegrationTest"
```
