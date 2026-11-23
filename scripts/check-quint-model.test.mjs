import test from 'node:test';
import assert from 'node:assert/strict';
import { extractQuintActions, validateQuintModel, validateItfTrace } from './check-quint-model.mjs';

test('extractQuintActions ignores init and step and returns API actions', () => {
  const qnt = `
    module sample {
      action init = all { true }
      action createTodo(id: str): bool = all { true }
      action deleteTodo(id: str): bool = all { true }
      action step = any { createTodo("1"), deleteTodo("1") }
    }
  `;
  assert.deepEqual([...extractQuintActions(qnt)].sort(), ['createTodo', 'deleteTodo']);
});

test('validateQuintModel flags actions missing from OpenAPI operationIds', () => {
  const qnt = `module sample { action unknownAction: bool = all { true } }`;
  const errors = validateQuintModel(qnt, new Set(['createTodo']));
  assert.equal(errors.length, 1);
  assert.match(errors[0], /unknownAction/);
});

test('validateItfTrace checks every transition lastAction against OpenAPI operationIds', () => {
  const validTrace = {
    states: [
      { '#meta': { index: 0 }, lastAction: 'init' },
      { '#meta': { index: 1 }, lastAction: 'createTodo' },
      { '#meta': { index: 2 }, lastAction: 'getTodo' },
    ],
  };
  assert.deepEqual(validateItfTrace(validTrace, new Set(['createTodo', 'getTodo'])), []);

  const invalidTrace = {
    states: [
      { '#meta': { index: 0 }, lastAction: 'init' },
      { '#meta': { index: 1 }, lastAction: 'nonExistentOp' },
    ],
  };
  assert.equal(validateItfTrace(invalidTrace, new Set(['createTodo'])).length, 1);
});
