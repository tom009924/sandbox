# ASM Production Dead-Code Analyzer

Minimal Spring Bean-aware JVM bytecode analyzer (OW2 ASM only) that scans `libs/*/build/classes/kotlin/main` and `services/*/build/classes/kotlin/main` to detect production dead code while excluding test references.

## Usage

```bash
# 1. Compile production sources
./gradlew classes

# 2. Check against baseline (fails CI on new dead code)
./gradlew -p tools/dead-code-analyzer run --args="$(pwd) --baseline $(pwd)/tools/dead-code-analyzer/baseline.txt"

# 3. Regenerate baseline after cleaning up dead code
./gradlew -p tools/dead-code-analyzer run --args="$(pwd) --update-baseline $(pwd)/tools/dead-code-analyzer/baseline.txt"
```
