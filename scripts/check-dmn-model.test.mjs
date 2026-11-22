import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import yaml from 'yaml';
import {
  collectOpenApiOperationIds,
  parseDmnModel,
  validateDmnModel,
} from './check-dmn-model.mjs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(__dirname, '..');

test('todo-decisions.dmn passes validation against OpenAPI', () => {
  const xml = fs.readFileSync(path.join(repoRoot, 'docs/models/todo-decisions.dmn'), 'utf8');
  const openApi = yaml.parse(fs.readFileSync(path.join(repoRoot, 'docs/dist/openapi.admin.yaml'), 'utf8'));
  const model = parseDmnModel(xml);
  const errors = validateDmnModel(model, collectOpenApiOperationIds(openApi));
  assert.deepEqual(errors, []);
  assert.equal(model.decisions.length, 4);
});

test('validateDmnModel detects unknown operationId and missing catch-all rule', () => {
  const invalidXml = `<?xml version="1.0" encoding="UTF-8"?>
  <definitions xmlns="https://www.omg.org/spec/DMN/20191111/MODEL/" id="broken">
    <decision id="sampleDecision" name="Sample">
      <extensionElements><todo:operations>nonExistentOp</todo:operations></extensionElements>
      <decisionTable id="t1" hitPolicy="FIRST">
        <input id="i1" label="flag"><inputExpression id="e1" typeRef="boolean"><text>flag</text></inputExpression></input>
        <output id="o1" name="decision" typeRef="string" />
        <rule id="r1">
          <inputEntry id="ie1"><text>true</text></inputEntry>
          <outputEntry id="oe1"><text>"ALLOW"</text></outputEntry>
        </rule>
      </decisionTable>
    </decision>
  </definitions>`;

  const model = parseDmnModel(invalidXml);
  const errors = validateDmnModel(model, new Set(['createTodo']));
  assert.ok(errors.some((e) => e.includes('unknown OpenAPI operationId "nonExistentOp"')));
  assert.ok(errors.some((e) => e.includes('catch-all')));
});
