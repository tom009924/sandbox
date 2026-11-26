import fs from 'node:fs';
import path from 'node:path';
import { parseScxml } from './check-scxml-model.mjs';

const rootDir = path.resolve(import.meta.dirname, '..');
const defaultScxmlFile = path.join(rootDir, 'docs', 'models', 'todo-lifecycle.scxml');
const defaultOutputFile = path.join(
  rootDir,
  'services',
  'todo-service',
  'build',
  'generated',
  'scxml',
  'src',
  'main',
  'kotlin',
  'com',
  'example',
  'todo',
  'domain',
  'lifecycle',
  'TodoLifecycleModel.kt',
);

export function toEnumConstant(name) {
  return name
    .replace(/([a-z0-9])([A-Z])/g, '$1_$2')
    .replace(/[^a-zA-Z0-9]+/g, '_')
    .toUpperCase();
}

function escapeKotlinString(value) {
  return value.replaceAll('\\', '\\\\').replaceAll('"', '\\"');
}

export function generateKotlinSource(model, packageName = 'com.example.todo.domain.lifecycle') {
  const stateEntries = model.states
    .map(s => `    ${toEnumConstant(s.id)}("${escapeKotlinString(s.id)}"),`)
    .join('\n');

  const uniqueEvents = [...new Set(model.transitions.map(t => t.event).filter(Boolean))].sort();
  const eventEntries = uniqueEvents
    .map(e => `    ${toEnumConstant(e)}("${escapeKotlinString(e)}"),`)
    .join('\n');

  const transitionEntries = model.transitions
    .map(t => {
      const condLiteral = t.cond ? `"${escapeKotlinString(t.cond)}"` : 'null';
      return [
        '        TodoLifecycleTransition(',
        `            source = TodoLifecycleState.${toEnumConstant(t.source)},`,
        `            event = TodoLifecycleEvent.${toEnumConstant(t.event)},`,
        `            condition = ${condLiteral},`,
        `            target = TodoLifecycleState.${toEnumConstant(t.target)},`,
        '        ),',
      ].join('\n');
    })
    .join('\n');

  return `// AUTO-GENERATED FROM docs/models/todo-lifecycle.scxml — DO NOT EDIT MANUALLY.
package ${packageName}

enum class TodoLifecycleState(val id: String) {
${stateEntries}
    ;

    companion object {
        fun fromCompleted(completed: Boolean): TodoLifecycleState =
            if (completed) COMPLETED else ACTIVE
    }
}

enum class TodoLifecycleEvent(val operationId: String) {
${eventEntries}
}

data class TodoLifecycleTransition(
    val source: TodoLifecycleState,
    val event: TodoLifecycleEvent,
    val condition: String?,
    val target: TodoLifecycleState,
)

object TodoLifecycleModel {
    private const val ACTIVE_LIMIT_GUARD = "activeCount < maxActiveTodos"

    val initialState: TodoLifecycleState = TodoLifecycleState.${toEnumConstant(model.initial)}

    val transitions: List<TodoLifecycleTransition> = listOf(
${transitionEntries}
    )

    fun allowedTargets(
        state: TodoLifecycleState,
        event: TodoLifecycleEvent,
    ): Set<TodoLifecycleState> = transitions
        .asSequence()
        .filter { it.source == state && it.event == event }
        .map { it.target }
        .toSet()

    fun isTransitionAllowed(
        from: TodoLifecycleState,
        event: TodoLifecycleEvent,
        to: TodoLifecycleState,
    ): Boolean = to in allowedTargets(from, event)

    fun requiresActiveLimitCheck(
        from: TodoLifecycleState,
        event: TodoLifecycleEvent,
        to: TodoLifecycleState,
    ): Boolean = transitions.any {
        it.source == from &&
            it.event == event &&
            it.target == to &&
            it.condition?.contains(ACTIVE_LIMIT_GUARD) == true
    }
}
`;
}

export function main() {
  const inputFile = process.argv[2] ? path.resolve(process.argv[2]) : defaultScxmlFile;
  const outputFile = process.argv[3] ? path.resolve(process.argv[3]) : defaultOutputFile;

  const xml = fs.readFileSync(inputFile, 'utf8');
  const model = parseScxml(xml);
  const kotlinSource = generateKotlinSource(model);

  fs.mkdirSync(path.dirname(outputFile), { recursive: true });
  fs.writeFileSync(outputFile, kotlinSource, 'utf8');
  console.log(`✓ Generated Kotlin lifecycle model at ${path.relative(rootDir, outputFile)}`);
}

if (process.argv[1] && path.resolve(process.argv[1]) === import.meta.filename) {
  main();
}
