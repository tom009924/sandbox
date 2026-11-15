import assert from 'node:assert/strict';
import test from 'node:test';
import { generateFlowchart, generateMarkdown, resolveRefName } from './generate-asyncapi-diagrams.mjs';

test('resolveRefName extracts trailing segment from JSON pointer', () => {
  assert.equal(resolveRefName('#/channels/todoEvents'), 'todoEvents');
  assert.equal(resolveRefName('#/components/schemas/TodoPayload'), 'TodoPayload');
  assert.equal(resolveRefName(undefined), '');
});

test('generateFlowchart renders mermaid flowchart for send operations', () => {
  const spec = {
    info: { title: 'Todo Service Events' },
    servers: { 'gcp-pubsub': { protocol: 'googlepubsub' } },
    channels: {
      todoEvents: { address: 'projects/{projectId}/topics/todo-events' },
    },
    operations: {
      publishTodoEvent: {
        action: 'send',
        channel: { $ref: '#/channels/todoEvents' },
        messages: [{ $ref: '#/channels/todoEvents/messages/todoEvent' }],
      },
    },
  };

  const chart = generateFlowchart(spec);
  assert.match(chart, /^```mermaid\nflowchart LR/);
  assert.match(chart, /Producer\["Todo Service Events"\]/);
  assert.match(chart, /Channel_todoEvents\["Channel: todoEvents/);
  assert.match(chart, /Producer -->\|publishTodoEvent \(send: todoEvent\)\| Channel_todoEvents/);
});

test('generateMarkdown includes operation and schema tables', () => {
  const spec = {
    info: { title: 'Sample Events', version: '1.0.0', description: 'Sample desc' },
    servers: {},
    channels: {
      sampleChannel: { address: 'topics/sample' },
    },
    operations: {
      sendSample: {
        action: 'send',
        summary: 'Send sample',
        channel: { $ref: '#/channels/sampleChannel' },
      },
    },
    components: {
      schemas: {
        SampleEvent: {
          required: ['id'],
          properties: {
            id: { type: 'string', format: 'uuid' },
            note: { type: 'string', nullable: true },
          },
        },
      },
    },
  };

  const md = generateMarkdown(spec);
  assert.match(md, /# 非同期イベント仕様・可視化ダイアグラム \(Sample Events v1\.0\.0\)/);
  assert.match(md, /\| `sendSample` \| `send` \| `sampleChannel` \| `topics\/sample` \| Send sample \|/);
  assert.match(md, /\| `id` \| `string` \| `uuid` \| Yes \| No \|/);
  assert.match(md, /\| `note` \| `string` \| - \| No \| Yes \|/);
});
