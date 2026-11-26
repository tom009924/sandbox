import test from 'node:test';
import assert from 'node:assert/strict';
import { parseScxml, validateScxmlModel } from './check-scxml-model.mjs';

test('parseScxml extracts states, transitions, and decoded conditions', () => {
  const xml = `
    <scxml xmlns="http://www.w3.org/2005/07/scxml" version="1.0" name="Sample" initial="NotCreated">
      <state id="NotCreated">
        <transition event="createTodo" cond="activeCount &lt; maxActiveTodos" target="Active"/>
      </state>
      <state id="Active">
        <transition event="deleteTodo" target="Deleted"/>
      </state>
      <final id="Deleted"/>
    </scxml>
  `;

  const model = parseScxml(xml);
  assert.equal(model.name, 'Sample');
  assert.equal(model.initial, 'NotCreated');
  assert.deepEqual(model.states.map(s => s.id), ['NotCreated', 'Active', 'Deleted']);
  assert.equal(model.transitions.length, 2);
  assert.equal(model.transitions[0].cond, 'activeCount < maxActiveTodos');
});

test('validateScxmlModel accepts valid model aligned with OpenAPI operationIds', () => {
  const model = {
    xmlns: 'http://www.w3.org/2005/07/scxml',
    version: '1.0',
    name: 'Sample',
    initial: 'NotCreated',
    states: [{ id: 'NotCreated', isFinal: false }, { id: 'Active', isFinal: false }],
    transitions: [{ source: 'NotCreated', event: 'createTodo', cond: null, target: 'Active' }],
  };

  const errors = validateScxmlModel(model, new Set(['createTodo', 'getTodo']));
  assert.deepEqual(errors, []);
});

test('validateScxmlModel reports unknown operationId and unknown target state', () => {
  const model = {
    xmlns: 'http://www.w3.org/2005/07/scxml',
    version: '1.0',
    name: 'Broken',
    initial: 'NotCreated',
    states: [{ id: 'NotCreated', isFinal: false }],
    transitions: [{ source: 'NotCreated', event: 'unknownOp', cond: null, target: 'Ghost' }],
  };

  const errors = validateScxmlModel(model, new Set(['createTodo']));
  assert.equal(errors.length, 2);
  assert.match(errors[0], /unknown state "Ghost"/);
  assert.match(errors[1], /unknownOp/);
});
