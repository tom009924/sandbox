# ASM Impact Analyzer

Minimal JVM bytecode impact analyzer (OW2 ASM only) that builds a reverse call graph across all `sandbox` modules (`libs:*`, `services:*`, `main` + `test`) and supports:

- **Source diff impact** (`--git-diff <ref>`): maps changed lines via bytecode `LineNumberTable` and auto-detects updated dependencies in `gradle.lockfile`.
- **Target simulation** (`--target <pattern>`): traces callers of any class or method.
- **Third-party dependency impact** (`--dep <group:artifact[:version]>`): inspects the dependency JAR from the Gradle cache (including `META-INF/spring/*.imports` and `META-INF/services/*` SPI detection) and traces direct callers -> impacted services, REST/gRPC endpoints, and tests.

## Usage

```bash
# 1. Compile main and test classes
./gradlew classes testClasses

# 2. Analyze impact from a git diff (source + gradle.lockfile updates)
./gradlew -p tools/impact-analyzer run --args="$(pwd) --git-diff origin/main"

# 3. Simulate third-party library update impact
./gradlew -p tools/impact-analyzer run --args="$(pwd) --dep io.jsonwebtoken:jjwt-api"

# 4. Simulate class/method change impact
./gradlew -p tools/impact-analyzer run --args="$(pwd) --target TodoRepositoryAdapter.save"
```
