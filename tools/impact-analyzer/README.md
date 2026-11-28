# ASM Impact Analyzer

Minimal JVM bytecode impact analyzer (OW2 ASM only) that builds a reverse call graph across all `sandbox` modules (`libs:*`, `services:*`, `main` + `test`) and maps changed lines from `git diff` (via bytecode `LineNumberTable`) to impacted services, REST/gRPC endpoints, and test classes.

## Usage

```bash
# 1. Compile main and test classes
./gradlew classes testClasses

# 2. Analyze impact from a git diff
./gradlew -p tools/impact-analyzer run --args="$(pwd) --git-diff origin/main"

# 3. Simulate impact for a specific class or method
./gradlew -p tools/impact-analyzer run --args="$(pwd) --target TodoRepositoryAdapter.save"
```
