import test from 'node:test';
import assert from 'node:assert/strict';
import {
  extractOpenApiOperationIds,
  extractArazzoOperationIds,
  findMissingOperationIds,
} from './check-arazzo-coverage.mjs';

test('extractOpenApiOperationIds collects all HTTP operationIds', () => {
  const doc = {
    paths: {
      '/api/projects': {
        get: { operationId: 'listProjects' },
        post: { operationId: 'createProject' },
        parameters: [],
      },
    },
  };
  assert.deepEqual([...extractOpenApiOperationIds(doc)].sort(), ['createProject', 'listProjects']);
});

test('extractArazzoOperationIds strips sourceDescriptions prefix', () => {
  const docs = [
    {
      workflows: [
        {
          workflowId: 'wf1',
          steps: [
            { stepId: 's1', operationId: '$sourceDescriptions.publicApi.createProject' },
            { stepId: 's2', operationId: '$sourceDescriptions.adminApi.getAdminStats' },
          ],
        },
      ],
    },
  ];
  assert.deepEqual([...extractArazzoOperationIds(docs)].sort(), ['createProject', 'getAdminStats']);
});

test('findMissingOperationIds returns uncovered operationIds unless ignored', () => {
  const openApiDoc = {
    paths: {
      '/api/projects': {
        get: { operationId: 'listProjects' },
        post: { operationId: 'createProject' },
      },
    },
  };
  const arazzoDocs = [
    {
      workflows: [
        {
          workflowId: 'wf1',
          steps: [{ stepId: 's1', operationId: '$sourceDescriptions.publicApi.listProjects' }],
        },
      ],
    },
  ];
  assert.deepEqual(findMissingOperationIds(openApiDoc, arazzoDocs), ['createProject']);
  assert.deepEqual(findMissingOperationIds(openApiDoc, arazzoDocs, new Set(['createProject'])), []);
});
