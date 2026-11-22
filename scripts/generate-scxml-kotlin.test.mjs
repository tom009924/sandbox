import test from 'node:test';
import assert from 'node:assert/strict';
import { generateKotlinSource, toEnumConstant } from './generate-scxml-kotlin.mjs';

test('toEnumConstant converts camelCase and PascalCase identifiers to UPPER_SNAKE_CASE', () => {
  assert.equal(toEnumConstant('NotCreated'), 'NOT_CREATED');
  assert.equal(toEnumConstant('createTodoSubtask'), 'CREATE_TODO_SUBTASK');
});

test('generateKotlinSource derives states, events, transitions, and guard helpers from SCXML', () => {
  const model = {
    name: 'Sample',
    initial: 'NotCreated',
    states: [
      { id: 'NotCreated', isFinal: false },
      { id: 'Active', isFinal: false },
      { id: 'Completed', isFinal: false },
    ],
    transitions: [
      { source: 'NotCreated', event: 'createTodo', cond: 'activeCount < maxActiveTodos', target: 'Active' },
      { source: 'Active', event: 'updateTodo', cond: 'completed == true', target: 'Completed' },
    ],
  };

  const kt = generateKotlinSource(model);
  assert.match(kt, /enum class TodoLifecycleState/);
  assert.match(kt, /NOT_CREATED\("NotCreated"\)/);
  assert.match(kt, /CREATE_TODO\("createTodo"\)/);
  assert.match(kt, /fun requiresActiveLimitCheck/);
  assert.match(kt, /condition = "activeCount < maxActiveTodos"/);
});
