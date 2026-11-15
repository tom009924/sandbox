# ADR-0001: 外部接続順・非同期イベントの Docs-as-Code 運用

## ステータス
Accepted

## コンテキスト
本サービスは Cloud Spanner、KMS (gRPC)、Cloud Pub/Sub、Email Notification Service (HTTP)、および Apigee プロキシと連携する。
外部接続の呼び出し順序や非同期イベント仕様を手書きドキュメントで管理すると、コード変更に追従できず陳腐化しやすい。

## 決定
1. **外部接続順の図（Mermaid）の自動生成と Drift Check**
   - `ExternalConnectionTracer` による動的トレースから [docs/architecture/external-connection-order.md](../architecture/external-connection-order.md) を `./gradlew generateExternalConnectionDiagrams` で生成する。
   - CI（`.github/workflows/ci.yml`）で生成コマンドを再実行し、`git diff --exit-code` によりドキュメントの更新漏れを検知する。
2. **同期 API / ワークフロー仕様との棲み分け**
   - 同期 REST API 仕様は `docs/openapi.yaml`（Spectral 検証）、E2E ワークフローは `docs/workflows/*.arazzo.yaml`（`npm run generate:diagrams`）で管理する。
   - 非同期 Pub/Sub イベント仕様は [docs/asyncapi.yaml](../asyncapi.yaml)（AsyncAPI 3.0.0）で管理する。

## 結果
- コード変更に伴う外部接続順の変化が PR 上のテキスト差分としてレビュー可能になり、ドキュメントと実装の乖離を CI で防止できる。
