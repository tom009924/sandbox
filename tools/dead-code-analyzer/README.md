# ASM Production Dead-Code Analyzer

Minimal JVM bytecode reachability analyzer (OW2 ASM only) that scans `libs/*/build/classes/kotlin/main` and `services/*/build/classes/kotlin/main` to detect production dead code while excluding test references.

## Usage

```bash
# Compile production sources if not already built
./gradlew classes

# Run dead-code analysis in a single command
./gradlew -p tools/dead-code-analyzer run --args="$(pwd)"
```
