# Kotlin Analysis API Production Dead-Code Analyzer

Minimal K2 semantic reachability analyzer (Kotlin Analysis API Standalone) that loads all `sandbox` `src/main` sources into a single `KaSourceModule` (excluding `src/test` / `src/integrationTest`) to detect production dead code.

## Usage

```bash
# 1. Compile main sources and export source roots + compile classpath
./gradlew --init-script tools/dead-code-analyzer/export-metadata.init.gradle.kts exportDeadCodeMetadata

# 2. Build standalone distribution and run
./gradlew -p tools/dead-code-analyzer installDist --no-daemon
./tools/dead-code-analyzer/build/install/dead-code-analyzer/bin/dead-code-analyzer \
  "$(pwd)/tools/dead-code-analyzer/build/sandbox-metadata.json"
```
