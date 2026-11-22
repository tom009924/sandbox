import test from 'node:test';
import assert from 'node:assert/strict';
import { generateStateDiagram } from './generate-scxml-diagrams.mjs';

test('generateStateDiagram renders Mermaid stateDiagram-v2 from SCXML model', () => {
  const model = {
    name: 'Sample',
    initial: 'NotCreated',
    states: [
      { id: 'NotCreated', isFinal: false },
      { id: 'Active', isFinal: false },
      { id: 'Deleted', isFinal: true },
    ],
    transitions: [
      { source: 'NotCreated', event: 'createTodo', cond: 'activeCount < maxActiveTodos', target: 'Active' },
      { source: 'Active', event: 'deleteTodo', cond: null, target: 'Deleted' },
    ],
  };

  const diagram = generateStateDiagram(model);
  assert.match(diagram, /stateDiagram-v2/);
  assert.match(diagram, /\[\*\] --> NotCreated/);
  assert.match(diagram, /NotCreated --> Active: createTodo \[activeCount < maxActiveTodos\]/);
  assert.match(diagram, /Active --> Deleted: deleteTodo/);
  assert.match(diagram, /Deleted --> \[\*\]/);
});
