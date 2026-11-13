# Agent Guidelines & Skills

本リポジトリで動作する AI コーディングエージェント（Antigravity および GitHub Copilot）向けの共通設定です。

## 共通ルール
詳細なコーディング規約および設計方針は [.github/rules/guidelines.md](.github/rules/guidelines.md) を参照してください。

- **Java/Kotlin**: Java 21, Kotlin 2.2, Spring Boot 4.0
- **スタイル/検証**: `./gradlew detekt`（Detekt 静的解析をパスすること）
- **コード品質 (Qodana)**: CI で JetBrains Qodana（[qodana.yaml](qodana.yaml)）による静的コード解析が実行されます
- **セキュリティ・OSS健全性・SBOM (Trivy, OpenSSF Scorecard)**: CI で Aqua Security Trivy（[trivy.yaml](trivy.yaml), [.trivyignore](.trivyignore)）による脆弱性・ライセンススキャンおよび CycloneDX SBOM の自動生成・保管、ならびに OpenSSF Scorecard（[.github/workflows/oss-health.yml](.github/workflows/oss-health.yml)）による依存OSSの持続可能性・活動性・放置リスク・サプライチェーンセキュリティ評価（JSONレポート保管）が実行されます。また Gradle Dependency Verification（`gradle/verification-metadata.xml`）により依存関係のチェックサム改ざんが防止されます
- **OpenAPI**: API変更時は必ず [docs/openapi.yaml](docs/openapi.yaml) と実装コードを同期し、`./gradlew lintOpenApi` (Spectral) をパスすること
- **全検証**: コミット前に必ず `./gradlew check` を実行すること
- **テスト戦略・テスト環境検証**: 全体テスト方針は [docs/testing/test-strategy.md](docs/testing/test-strategy.md)、デプロイ後の環境検証およびリリース前チェックは [docs/testing/test-environment-specification.md](docs/testing/test-environment-specification.md) を参照すること

## トークン消費・コンテキスト効率化 (ccusage 連携 & ベストプラクティス)
AI コーディングエージェントのコンテキスト窓圧迫・トークン浪費・レイテンシ増大を防ぐため、以下の運用を徹底してください:
- **段階的検証 (Phased Verification)**: 実装途中の都度の全検証（`./gradlew check`）は避け、変更対象の静的解析（`./gradlew --offline detekt`）や対象テスト（`./gradlew --offline test --tests "<クラス名>"`）をピンポイントで実行する。全検証はコミット直前に 1 度のみ実施する。
- **ターミナル出力の抑制**: 大量のログを出力するコマンドは `--quiet` やパイプ (`head` / `grep`) を適宜利用する。テスト失敗時は Gradle の `testLogging` により失敗原因が直接コンソールに出力されるため、余計なレポートファイル探索を省略する。
- **トークン・セッション使用量の把握**: `npm run usage`（または `ccusage -O`）を実行することで、セッションや日別のトークン使用量・コストを即座に確認可能。

## 利用可能なスキル (Workflows & Runbooks)
タスクに応じて、[.github/skills/](.github/skills/) 配下の各スキルを参照・実行してください:
- [run-tests](.github/skills/run-tests/SKILL.md): 単体・統合テスト、Detekt 静的解析の実行とエラー対処
- [update-api](.github/skills/update-api/SKILL.md): OpenAPI仕様と実装コードの整合性を保つAPI更新手順
- [run-app](.github/skills/run-app/SKILL.md): Cloud Spanner EmulatorやApigee Emulatorを利用したSpring Bootアプリケーションの起動とAPI確認
