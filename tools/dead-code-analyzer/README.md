# Kotlin Analysis API Production Dead-Code Analyzer

Standalone K2 semantic reachability analyzer (powered by Kotlin Analysis API Standalone) that detects production dead code across all `sandbox` modules (`libs:*`, `services:*`) while excluding references from `src/test` and `src/integrationTest`.

## Usage

```bash
# 1. Compile main source sets and export module metadata
./gradlew --init-script tools/dead-code-analyzer/export-metadata.init.gradle.kts exportDeadCodeMetadata

# 2. Build standalone distribution and run against production sources only
./gradlew -p tools/dead-code-analyzer :analysis-api-analyzer:installDist --no-daemon
./tools/dead-code-analyzer/analysis-api-analyzer/build/install/analysis-api-analyzer/bin/analysis-api-analyzer \
  "$(pwd)/tools/dead-code-analyzer/build/sandbox-metadata.json"
```

## Characteristics

- Loads only `src/main` source roots into `KaSourceModule`s, excluding test-only references by construction.
- Resolves Kotlin symbols (`KtReference.resolveToSymbols()`) and override hierarchies (`allOverriddenSymbols`) across modules.
- Detects unreachable classes, functions (including interface declarations), and properties with exact source file and line numbers.
