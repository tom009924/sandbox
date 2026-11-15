import fs from 'node:fs';
import path from 'node:path';
import YAML from 'yaml';

const rootDir = path.resolve(import.meta.dirname, '..');
const defaultOpenApiFile = path.join(rootDir, 'docs', 'dist', 'openapi.admin.yaml');
const defaultFormalDir = path.join(rootDir, 'docs', 'formal');

const MUTATING_HTTP_METHODS = new Set(['post', 'put', 'patch', 'delete']);

// Mutating operationIds explicitly exempted from formal TLA+ modeling (with rationale).
export const EXEMPT_OPERATION_IDS = new Set([
  'registerUser',       // Stateless user creation
  'loginUser',          // Stateless JWT issuance
  'updateUserSettings', // Simple upsert without cross-entity invariants
  'createProject',      // Project RBAC model planned in separate ProjectRbac.tla
  'updateProject',      // Project RBAC model planned in separate ProjectRbac.tla
  'deleteProject',      // Project RBAC model planned in separate ProjectRbac.tla
  'addProjectMember',   // Project RBAC model planned in separate ProjectRbac.tla
  'removeProjectMember', // Project RBAC model planned in separate ProjectRbac.tla
]);

export function extractMutatingOperationIds(openApiDoc) {
  const operationIds = new Set();
  const paths = openApiDoc?.paths || {};
  for (const pathItem of Object.values(paths)) {
    if (!pathItem || typeof pathItem !== 'object') continue;
    for (const [method, operation] of Object.entries(pathItem)) {
      if (!MUTATING_HTTP_METHODS.has(method.toLowerCase())) continue;
      if (operation?.operationId) {
        operationIds.add(operation.operationId);
      }
    }
  }
  return operationIds;
}

export function extractAllOperationIds(openApiDoc) {
  const operationIds = new Set();
  const paths = openApiDoc?.paths || {};
  for (const pathItem of Object.values(paths)) {
    if (!pathItem || typeof pathItem !== 'object') continue;
    for (const operation of Object.values(pathItem)) {
      if (operation && typeof operation === 'object' && operation.operationId) {
        operationIds.add(operation.operationId);
      }
    }
  }
  return operationIds;
}

export function extractFormalOperationIds(tlaContents) {
  const covered = new Set();
  const regex = /\\\*\s*@operationIds?\s+([^\r\n]+)/g;
  for (const content of tlaContents) {
    for (const match of content.matchAll(regex)) {
      const ids = match[1].split(',').map(s => s.trim()).filter(Boolean);
      for (const id of ids) {
        covered.add(id);
      }
    }
  }
  return covered;
}

export function checkFormalCoverage(openApiDoc, tlaContents, exemptIds = EXEMPT_OPERATION_IDS) {
  const mutating = extractMutatingOperationIds(openApiDoc);
  const allDefined = extractAllOperationIds(openApiDoc);
  const modeled = extractFormalOperationIds(tlaContents);

  const missing = [...mutating]
    .filter(id => !modeled.has(id) && !exemptIds.has(id))
    .sort();

  const stale = [...modeled]
    .filter(id => !allDefined.has(id))
    .sort();

  return { missing, stale };
}

export function main() {
  if (!fs.existsSync(defaultOpenApiFile)) {
    console.error(`Bundled OpenAPI spec not found: ${defaultOpenApiFile}. Run "npm run bundle:openapi" first.`);
    process.exit(1);
  }

  if (!fs.existsSync(defaultFormalDir)) {
    console.error(`Formal specs directory not found: ${defaultFormalDir}`);
    process.exit(1);
  }

  const tlaFiles = fs.readdirSync(defaultFormalDir)
    .filter(f => f.endsWith('.tla'))
    .map(f => path.join(defaultFormalDir, f));

  if (tlaFiles.length === 0) {
    console.error(`No .tla files found in ${defaultFormalDir}`);
    process.exit(1);
  }

  const openApiDoc = YAML.parse(fs.readFileSync(defaultOpenApiFile, 'utf8'));
  const tlaContents = tlaFiles.map(f => fs.readFileSync(f, 'utf8'));
  const { missing, stale } = checkFormalCoverage(openApiDoc, tlaContents);

  if (missing.length > 0) {
    console.error(
      `The following mutating OpenAPI operationIds are not modeled in docs/formal/*.tla (or exempted):\n` +
      missing.map(id => `  - ${id}`).join('\n'),
    );
  }

  if (stale.length > 0) {
    console.error(
      `The following @operationId annotations in docs/formal/*.tla do not exist in OpenAPI:\n` +
      stale.map(id => `  - ${id}`).join('\n'),
    );
  }

  if (missing.length > 0 || stale.length > 0) {
    process.exit(1);
  }

  console.log('All mutating OpenAPI operationIds are covered by formal specs (or explicitly exempted), and no stale annotations exist.');
}

if (import.meta.url === `file://${process.argv[1]}`) {
  main();
}
