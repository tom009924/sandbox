# Kotlin Dev Container

このプロジェクトは、VS Code の Dev Containers で Kotlin を開発するための最小構成です。

## 使い方

1. VS Code でこのフォルダを開く
2. `Dev Containers: Reopen in Container` を実行する
3. コンテナ起動後、自動で Gradle ラッパーが生成される
4. ターミナルで次を実行する

```bash
./gradlew run
```

## 含まれているもの

- Java 21
- Kotlin 1.9.24
- Gradle
- Kotlin / Java / Gradle の VS Code 拡張
