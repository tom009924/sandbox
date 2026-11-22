import fs from 'node:fs';
import path from 'node:path';
import YAML from 'yaml';
import { extractOpenApiOperationIds } from './check-arazzo-coverage.mjs';

const rootDir = path.resolve(import.meta.dirname, '..');
const defaultOpenApiFile = path.join(rootDir, 'docs', 'dist', 'openapi.admin.yaml');
const defaultModelsDir = path.join(rootDir, 'docs', 'models');

const INTERNAL_ACTIONS = new Set(['init', 'step']);

export function extractQuintActions(qntContent) {
  const withoutComments = qntContent
    .replace(/\/\/[^\n]*/g, '')
    .replace(/\/\*[\s\S]*?\*\//g, '');
  const actions = new Set();
  const regex = /\baction\s+([a-zA-Z_][a-zA-Z0-9_]*)\b/g;
  let match;
  while ((match = regex.exec(withoutComments)) !== null) {
    const name = match[1];
    if (!INTERNAL_ACTIONS.has(name)) {
      actions.add(name);
    }
  }
  return actions;
}

export function validateQuintModel(qntContent, openApiOperationIds) {
  const errors = [];
  const actions = extractQuintActions(qntContent);
  if (actions.size === 0) {
    errors.push('No OpenAPI-mapped actions found in Quint module');
  }
  for (const action of actions) {
    if (!openApiOperationIds.has(action)) {
      errors.push(`Quint action "${action}" does not match any OpenAPI operationId`);
    }
  }
  return errors;
}

export function validateItfTrace(traceJson, openApiOperationIds) {
  const errors = [];
  if (!Array.isArray(traceJson?.states) || traceJson.states.length < 2) {
    errors.push('ITF trace must contain at least 2 states (initial + transitions)');
    return errors;
  }
  for (let i = 1; i < traceJson.states.length; i++) {
    const action = traceJson.states[i]?.lastAction;
    if (typeof action !== 'string' || !openApiOperationIds.has(action)) {
      errors.push(`ITF trace state[${i}] has invalid lastAction "${action}"`);
    }
  }
  return errors;
}

export function main() {
  if (!fs.existsSync(defaultOpenApiFile)) {
    console.error(`Bundled OpenAPI spec not found: ${defaultOpenApiFile}. Run "npm run bundle:openapi" first.`);
    process.exit(1);
  }
  const openApiDoc = YAML.parse(fs.readFileSync(defaultOpenApiFile, 'utf8'));
  const openApiOperationIds = extractOpenApiOperationIds(openApiDoc);

  const qntFiles = fs.readdirSync(defaultModelsDir)
    .filter(f => f.endsWith('.qnt'))
    .sort()
    .map(f => path.join(defaultModelsDir, f));

  if (qntFiles.length === 0) {
    console.error(`No Quint model files found in ${defaultModelsDir}`);
    process.exit(1);
  }

  let hasErrors = false;
  for (const file of qntFiles) {
    const content = fs.readFileSync(file, 'utf8');
    const errors = validateQuintModel(content, openApiOperationIds);
    if (errors.length > 0) {
      hasErrors = true;
      console.error(`Validation failed for ${path.relative(rootDir, file)}:`);
      errors.forEach(e => console.error(`  - ${e}`));
    }
  }

  const tracesDir = path.join(defaultModelsDir, 'traces');
  if (fs.existsSync(tracesDir)) {
    const traceFiles = fs.readdirSync(tracesDir)
      .filter(f => f.endsWith('.itf.json'))
      .sort()
      .map(f => path.join(tracesDir, f));
    for (const traceFile of traceFiles) {
      const trace = JSON.parse(fs.readFileSync(traceFile, 'utf8'));
      const errors = validateItfTrace(trace, openApiOperationIds);
      if (errors.length > 0) {
        hasErrors = true;
        console.error(`Validation failed for ${path.relative(rootDir, traceFile)}:`);
        errors.forEach(e => console.error(`  - ${e}`));
      }
    }
  }

  if (hasErrors) {
    process.exit(1);
  }

  console.log(`All Quint models (${qntFiles.length}) and ITF traces are valid and aligned with OpenAPI operationIds.`);
}

if (process.argv[1] && path.resolve(process.argv[1]) === import.meta.filename) {
  main();
}
