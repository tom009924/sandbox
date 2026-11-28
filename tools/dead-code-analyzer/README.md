# ASM Production Dead-Code Analyzer

Standalone JVM bytecode reachability analyzer (powered by OW2 ASM) that detects production dead code across all `sandbox` modules (`libs:*`, `services:*`) while excluding references from `src/test` and `src/integrationTest`.

## Usage

```bash
# 1. Compile main source sets and export module metadata
./gradlew --init-script tools/dead-code-analyzer/export-metadata.init.gradle.kts exportDeadCodeMetadata

# 2. Run ASM dead-code analyzer against production classes only
./gradlew -p tools/dead-code-analyzer :asm-analyzer:run \
  --args="$(pwd)/tools/dead-code-analyzer/build/sandbox-metadata.json"
```

## Characteristics

- Scans compiled `build/classes/kotlin/main` bytecode only, naturally excluding test-only references.
- Cross-module reachability analysis (BFS) from Spring Boot, gRPC, OpenAPI, Spanner, and `main` entry points.
- Very fast (~0.4s across 553 class files) with minimal dependencies (`org.ow2.asm:asm`).
