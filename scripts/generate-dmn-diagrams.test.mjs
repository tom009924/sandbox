import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { parseDmnModel } from './check-dmn-model.mjs';
import { renderDmnMarkdown } from './generate-dmn-diagrams.mjs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(__dirname, '..');

test('renderDmnMarkdown renders Mermaid DRD and all decision tables', () => {
  const xml = fs.readFileSync(path.join(repoRoot, 'docs/models/todo-decisions.dmn'), 'utf8');
  const markdown = renderDmnMarkdown(parseDmnModel(xml));
  assert.match(markdown, /```mermaid/);
  assert.match(markdown, /activeTodoLimitDecision/);
  assert.match(markdown, /projectMemberRemovalDecision/);
  assert.match(markdown, /idempotencyDecision/);
  assert.match(markdown, /todoAssigneeDecision/);
  assert.match(markdown, /`active_create_limit_exceeded`/);
});
