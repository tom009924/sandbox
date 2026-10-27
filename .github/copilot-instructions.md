# GitHub Copilot Instructions

本リポジトリで作業する際は、以下のルールおよび `.github/skills/` 配下の手順書に従ってください。

## 開発ルール & ガイドライン
詳細なガイドラインは [.github/rules/guidelines.md](.github/rules/guidelines.md) に定義されています。
- **言語 & フレームワーク**: Kotlin 2.2, Java 21, Spring Boot 4.0
- **コードスタイル**: Detekt のルールに準拠してください。
- **OpenAPI 仕様との同期**: APIの追加・変更・削除を行う場合は、必ず `docs/openapi.yaml` も同時に更新してください。統合テストで `swagger-parser` と Kotest により仕様との不整合が検知されます。
- **検証コマンド**: 作業完了時は `./gradlew check` を実行し、全テストと Detekt のパスを確認してください。

## タスク別スキル・手順書の参照
以下のタスクを実行する際は、対応する手順書を参照してください:
- テスト・品質検証の実行: [.github/skills/run-tests/SKILL.md](.github/skills/run-tests/SKILL.md)
- APIの変更・追加: [.github/skills/update-api/SKILL.md](.github/skills/update-api/SKILL.md)
- アプリケーション起動と確認: [.github/skills/run-app/SKILL.md](.github/skills/run-app/SKILL.md)
