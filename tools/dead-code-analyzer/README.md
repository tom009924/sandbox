# Kotlin Analysis API Production Dead-Code Analyzer

Minimal Spring Bean-aware K2 semantic analyzer (Kotlin Analysis API Standalone) that loads all `sandbox` `src/main` sources into a single `KaSourceModule` (excluding `src/test` / `src/integrationTest`) to detect production dead code.

## Usage

```bash
# 1. Compile main sources and export source roots + compile classpath
./gradlew --init-script tools/dead-code-analyzer/export-metadata.init.gradle.kts exportDeadCodeMetadata

# 2. Build standalone distribution
./gradlew -p tools/dead-code-analyzer installDist --no-daemon

# 3. Check against baseline (fails CI on new dead code)
./tools/dead-code-analyzer/build/install/dead-code-analyzer/bin/dead-code-analyzer \
  "$(pwd)/tools/dead-code-analyzer/build/sandbox-metadata.json" \
  --baseline "$(pwd)/tools/dead-code-analyzer/baseline.txt"

# 4. Regenerate baseline after cleaning up dead code
./tools/dead-code-analyzer/build/install/dead-code-analyzer/bin/dead-code-analyzer \
  "$(pwd)/tools/dead-code-analyzer/build/sandbox-metadata.json" \
  --update-baseline "$(pwd)/tools/dead-code-analyzer/baseline.txt"
```
