import assert from 'node:assert/strict';
import test from 'node:test';
import {
  checkFormalCoverage,
  extractFormalOperationIds,
  extractMutatingOperationIds,
} from './check-formal-coverage.mjs';

test('extractMutatingOperationIds ignores GET and collects mutating methods', () => {
  const openApiDoc = {
    paths: {
      '/todos': {
        get: { operationId: 'listTodos' },
        post: { operationId: 'createTodo' },
      },
      '/todos/{id}': {
        patch: { operationId: 'patchTodo' },
        delete: { operationId: 'deleteTodo' },
      },
    },
  };

  assert.deepEqual(
    [...extractMutatingOperationIds(openApiDoc)].sort(),
    ['createTodo', 'deleteTodo', 'patchTodo'],
  );
});

test('extractFormalOperationIds parses single and comma-separated annotations', () => {
  const tla = `
    \\* @operationId createTodo
    StartCreate == TRUE
    \\* @operationIds updateTodo, patchTodo
    Patch == TRUE
  `;

  assert.deepEqual(
    [...extractFormalOperationIds([tla])].sort(),
    ['createTodo', 'patchTodo', 'updateTodo'],
  );
});

test('checkFormalCoverage detects missing and stale operationIds', () => {
  const openApiDoc = {
    paths: {
      '/todos': {
        post: { operationId: 'createTodo' },
      },
      '/todos/{id}': {
        delete: { operationId: 'deleteTodo' },
      },
    },
  };

  const tla = `
    \\* @operationId createTodo
    StartCreate == TRUE
    \\* @operationId legacyRemovedOp
    OldOp == TRUE
  `;

  const result = checkFormalCoverage(openApiDoc, [tla], new Set());
  assert.deepEqual(result.missing, ['deleteTodo']);
  assert.deepEqual(result.stale, ['legacyRemovedOp']);
});

test('checkFormalCoverage passes when all mutating ops are modeled or exempted', () => {
  const openApiDoc = {
    paths: {
      '/auth/login': {
        post: { operationId: 'loginUser' },
      },
      '/todos': {
        post: { operationId: 'createTodo' },
      },
    },
  };

  const tla = `\\* @operationId createTodo`;
  const result = checkFormalCoverage(openApiDoc, [tla], new Set(['loginUser']));
  assert.deepEqual(result.missing, []);
  assert.deepEqual(result.stale, []);
});
