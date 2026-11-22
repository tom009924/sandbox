import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { parseDmnModel } from './check-dmn-model.mjs';
import { generateDmnKotlin } from './generate-dmn-kotlin.mjs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(__dirname, '..');

test('generateDmnKotlin generates typed Kotlin evaluator for all decisions', () => {
  const xml = fs.readFileSync(path.join(repoRoot, 'docs/models/todo-decisions.dmn'), 'utf8');
  const kotlin = generateDmnKotlin(parseDmnModel(xml));
  assert.match(kotlin, /package com\.example\.todo\.domain\.decision/);
  assert.match(kotlin, /object TodoDecisionModel/);
  assert.match(kotlin, /fun evaluateActiveTodoLimitDecision/);
  assert.match(kotlin, /fun evaluateProjectMemberRemovalDecision/);
  assert.match(kotlin, /fun evaluateIdempotencyDecision/);
  assert.match(kotlin, /fun evaluateTodoAssigneeDecision/);
  assert.match(kotlin, /matchedRuleId = "member_last_owner_protected"/);
});
