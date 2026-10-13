# プロジェクト共通コーディング規約・エージェントガイドライン

本リポジトリ（Kotlin Spring Boot CRUD API）で作業する AI コーディングエージェント（Antigravity, GitHub Copilot）および開発者向けの共通ガイドラインです。

## 1. 技術スタック
- **言語**: Kotlin 1.9 (Java 21)
- **フレームワーク**: Spring Boot 3.3 (Web, Data JPA, Validation)
- **データベース**: Cloud Spanner (ローカル開発・テストではCloud Spanner Emulator)
- **静的解析**: Detekt 1.23
- **テスト**: JUnit 5, MockMvc, REST Assured, Atlassian OpenAPI Request Validator
- **API仕様**: OpenAPI 3.0.3 ([docs/openapi.yaml](docs/openapi.yaml))

## 2. コーディングルール
1. **コードスタイル & 静的解析**:
   - Detekt のルールに準拠すること。
   - `./gradlew detekt` で静的解析を実施し、指摘事項を解消すること（自動修正可能ルールは自動修正されます）。
2. **OpenAPI仕様ファースト / 同期**:
   - APIのエンドポイント、リクエスト/レスポンス形式、バリデーションルールを変更・追加する場合は、必ず [`docs/openapi.yaml`](docs/openapi.yaml) も同時に更新すること。
   - 統合テスト（`integrationTest`）で OpenAPI 仕様との整合性が自動検証されます。
3. **エラーハンドリング**:
   - 不正なリクエストは `400 Bad Request`、リソース不在時は `404 Not Found` を返すこと。

## 3. テストと検証
変更完了後は必ず次のコマンドで全検証を実行し、エラーがないことを確認すること:
```bash
./gradlew check
```
※ `check` タスクにより Detekt、ユニットテスト、結合テストが順に実行されます。

## 4. スキル・手順書（Runbook）の参照
特定のタスクを実施する際は、[.github/skills/](.github/skills/) 配下の各手順書（`SKILL.md`）を参照してください:
- テストと検証: [.github/skills/run-tests/SKILL.md](.github/skills/run-tests/SKILL.md)
- APIの変更・追加: [.github/skills/update-api/SKILL.md](.github/skills/update-api/SKILL.md)
- アプリケーション起動と動作確認: [.github/skills/run-app/SKILL.md](.github/skills/run-app/SKILL.md)
