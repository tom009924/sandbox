import fs from 'node:fs';
import path from 'node:path';
import YAML from 'yaml';

const rootDir = path.resolve(import.meta.dirname, '..');
const defaultOpenApiFile = path.join(rootDir, 'docs', 'dist', 'openapi.admin.yaml');
const defaultWorkflowsDir = path.join(rootDir, 'docs', 'workflows');

const HTTP_METHODS = new Set(['get', 'put', 'post', 'delete', 'patch', 'options', 'head', 'trace']);

// Explicitly excluded operationIds that do not require an Arazzo workflow step.
export const IGNORED_OPERATION_IDS = new Set();

export function extractOpenApiOperationIds(openApiDoc) {
  const operationIds = new Set();
  const paths = openApiDoc?.paths || {};
  for (const pathItem of Object.values(paths)) {
    if (!pathItem || typeof pathItem !== 'object') continue;
    for (const [method, operation] of Object.entries(pathItem)) {
      if (!HTTP_METHODS.has(method.toLowerCase())) continue;
      if (operation?.operationId) {
        operationIds.add(operation.operationId);
      }
    }
  }
  return operationIds;
}

export function normalizeArazzoOperationId(rawOperationId) {
  if (typeof rawOperationId !== 'string' || rawOperationId.length === 0) return null;
  const prefixMatch = rawOperationId.match(/^\$sourceDescriptions\.[^.]+\.(.+)$/);
  return prefixMatch ? prefixMatch[1] : rawOperationId;
}

export function extractArazzoOperationIds(arazzoDocs) {
  const covered = new Set();
  for (const doc of arazzoDocs) {
    for (const wf of doc?.workflows || []) {
      for (const step of wf?.steps || []) {
        const opId = normalizeArazzoOperationId(step?.operationId);
        if (opId) covered.add(opId);
      }
    }
  }
  return covered;
}

export function findMissingOperationIds(openApiDoc, arazzoDocs, ignoredIds = IGNORED_OPERATION_IDS) {
  const defined = extractOpenApiOperationIds(openApiDoc);
  const covered = extractArazzoOperationIds(arazzoDocs);
  return [...defined].filter(id => !covered.has(id) && !ignoredIds.has(id)).sort();
}

export function main() {
  if (!fs.existsSync(defaultOpenApiFile)) {
    console.error(`Bundled OpenAPI spec not found: ${defaultOpenApiFile}. Run "npm run bundle:openapi" first.`);
    process.exit(1);
  }

  const openApiDoc = YAML.parse(fs.readFileSync(defaultOpenApiFile, 'utf8'));
  const workflowFiles = fs.readdirSync(defaultWorkflowsDir)
    .filter(f => f.endsWith('.arazzo.yaml') || f.endsWith('.arazzo.yml'))
    .map(f => path.join(defaultWorkflowsDir, f));

  if (workflowFiles.length === 0) {
    console.error(`No Arazzo workflow files found in ${defaultWorkflowsDir}`);
    process.exit(1);
  }

  const arazzoDocs = workflowFiles.map(f => YAML.parse(fs.readFileSync(f, 'utf8')));
  const missing = findMissingOperationIds(openApiDoc, arazzoDocs);

  if (missing.length > 0) {
    console.error(
      `The following OpenAPI operationIds are not covered by any Arazzo workflow in docs/workflows/*.arazzo.yaml:\n` +
      missing.map(id => `  - ${id}`).join('\n'),
    );
    process.exit(1);
  }

  console.log('All OpenAPI operationIds are covered by Arazzo workflows.');
}

if (import.meta.url === `file://${process.argv[1]}`) {
  main();
}
