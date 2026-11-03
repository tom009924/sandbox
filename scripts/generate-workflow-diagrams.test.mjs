import test from 'node:test';
import assert from 'node:assert/strict';
import {
  extractExpectedStatus,
  generateSequenceDiagram,
  generateMarkdown,
} from './generate-workflow-diagrams.mjs';

test('extractExpectedStatus extracts status code from conditions', () => {
  assert.equal(extractExpectedStatus([{ condition: '$statusCode == 201' }]), '201');
  assert.equal(extractExpectedStatus([{ condition: '$statusCode == 204' }]), '204');
  assert.equal(extractExpectedStatus([{ condition: '$statusCode==400' }]), '400');
});

test('extractExpectedStatus falls back to 200 when not found or invalid', () => {
  assert.equal(extractExpectedStatus([]), '200');
  assert.equal(extractExpectedStatus(null), '200');
  assert.equal(extractExpectedStatus(undefined), '200');
  assert.equal(extractExpectedStatus([{ condition: '$response.header != null' }]), '200');
});

test('generateSequenceDiagram builds valid Mermaid sequence diagram', () => {
  const workflow = {
    workflowId: 'test-wf',
    steps: [
      {
        stepId: 'stepOne',
        description: 'First step description',
        successCriteria: [{ condition: '$statusCode == 201' }],
        outputs: {
          token: '$response.body#/token',
          userId: '$response.body#/id',
        },
      },
      {
        stepId: 'stepTwo',
        successCriteria: [{ condition: '$statusCode == 200' }],
      },
    ],
  };

  const output = generateSequenceDiagram(workflow);

  assert.match(output, /^```mermaid\nsequenceDiagram\n\s+autonumber/);
  assert.match(output, /Client->>API: stepOne: First step description/);
  assert.match(output, /API-->>Client: 201 \(outputs: token, userId\)/);
  assert.match(output, /Client->>API: stepTwo/);
  assert.match(output, /API-->>Client: 200/);
  assert.match(output, /```$/);
});

test('generateMarkdown generates full document with header and workflows', (t) => {
  // Test using the actual todo-workflows.arazzo.yaml file in docs/workflows
  const sampleArazzoFile = new URL('../docs/workflows/todo-workflows.arazzo.yaml', import.meta.url).pathname;
  const markdown = generateMarkdown([sampleArazzoFile]);

  assert.match(markdown, /^# E2E ワークフロー仕様・可視化ダイアグラム/);
  assert.match(markdown, /## Todo API Workflows/);
  assert.match(markdown, /### ワークフロー: `user-todo-lifecycle`/);
  assert.match(markdown, /Client->>API: registerUser: Register a new end-user account/);
  assert.match(markdown, /API-->>Client: 201 \(outputs: userId, userName\)/);
  assert.match(markdown, /### ワークフロー: `admin-audit-lifecycle`/);
});
