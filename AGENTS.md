# Agent Guidelines & Skills

本リポジトリで動作する AI コーディングエージェント（Antigravity および GitHub Copilot）向けの共通設定です。

## 共通ルール
詳細なコーディング規約および設計方針は [.github/rules/guidelines.md](.github/rules/guidelines.md) を参照してください。

- **Java/Kotlin**: Java 21, Kotlin 1.9, Spring Boot 3.3
- **スタイル/検証**: `./gradlew detekt`（Detekt 静的解析をパスすること）
- **コード品質 (Qodana)**: CI で JetBrains Qodana（[qodana.yaml](qodana.yaml)）による静的コード解析が実行されます
- **セキュリティスキャン (Trivy)**: CI で Aqua Security Trivy（[trivy.yaml](trivy.yaml), [.trivyignore](.trivyignore)）による脆弱性・設定ミススキャンが実行され、未トリアージの重大脆弱性検出時は失敗します
- **OpenAPI**: API変更時は必ず [docs/openapi.yaml](docs/openapi.yaml) と実装コードを同期し、`./gradlew lintOpenApi` (Spectral) をパスすること
- **全検証**: コミット前に必ず `./gradlew check` を実行すること

## 利用可能なスキル (Workflows & Runbooks)
タスクに応じて、[.github/skills/](.github/skills/) 配下の各スキルを参照・実行してください:
- [run-tests](.github/skills/run-tests/SKILL.md): 単体・統合テスト、Detekt 静的解析の実行とエラー対処
- [update-api](.github/skills/update-api/SKILL.md): OpenAPI仕様と実装コードの整合性を保つAPI更新手順
- [run-app](.github/skills/run-app/SKILL.md): Cloud Spanner Emulatorを利用したSpring Bootアプリケーションの起動とAPI確認
