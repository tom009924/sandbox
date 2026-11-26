import fs from 'node:fs';
import path from 'node:path';
import YAML from 'yaml';
import { extractOpenApiOperationIds } from './check-arazzo-coverage.mjs';

const rootDir = path.resolve(import.meta.dirname, '..');
const defaultOpenApiFile = path.join(rootDir, 'docs', 'dist', 'openapi.admin.yaml');
const defaultModelsDir = path.join(rootDir, 'docs', 'models');
const SCXML_NAMESPACE = 'http://www.w3.org/2005/07/scxml';

function decodeXmlEntities(value) {
  return value
    .replaceAll('&amp;', '&')
    .replaceAll('&lt;', '<')
    .replaceAll('&gt;', '>')
    .replaceAll('&quot;', '"')
    .replaceAll('&apos;', "'");
}

function parseAttributes(attrString) {
  const attrs = {};
  const regex = /([a-zA-Z_:][\w:.-]*)\s*=\s*"([^"]*)"/g;
  let match;
  while ((match = regex.exec(attrString)) !== null) {
    attrs[match[1]] = decodeXmlEntities(match[2]);
  }
  return attrs;
}

export function parseScxml(xmlContent) {
  const withoutComments = xmlContent.replace(/<!--[\s\S]*?-->/g, '');
  const rootMatch = withoutComments.match(/<scxml\b([^>]*)>/);
  if (!rootMatch) {
    throw new Error('Missing <scxml> root element');
  }
  const rootAttrs = parseAttributes(rootMatch[1]);
  const states = new Map();
  const transitions = [];

  const stateRegex = /<(state|final)\b([^>]*?)(?:\/>|>([\s\S]*?)<\/\1>)/g;
  let stateMatch;
  while ((stateMatch = stateRegex.exec(withoutComments)) !== null) {
    const [, tagName, rawStateAttrs, innerXml = ''] = stateMatch;
    const stateAttrs = parseAttributes(rawStateAttrs);
    const stateId = stateAttrs.id;
    if (!stateId) {
      throw new Error(`Found <${tagName}> without id attribute`);
    }
    states.set(stateId, { id: stateId, isFinal: tagName === 'final' });

    const transitionRegex = /<transition\b([^>]*?)(?:\/>|>([\s\S]*?)<\/transition>)/g;
    let transMatch;
    while ((transMatch = transitionRegex.exec(innerXml)) !== null) {
      const transAttrs = parseAttributes(transMatch[1]);
      transitions.push({
        source: stateId,
        event: transAttrs.event || null,
        cond: transAttrs.cond || null,
        target: transAttrs.target || stateId,
      });
    }
  }

  return {
    xmlns: rootAttrs.xmlns || null,
    version: rootAttrs.version || null,
    name: rootAttrs.name || null,
    initial: rootAttrs.initial || null,
    states: [...states.values()],
    transitions,
  };
}

export function validateScxmlModel(model, openApiOperationIds) {
  const errors = [];
  const stateIds = new Set(model.states.map(s => s.id));

  if (model.xmlns !== SCXML_NAMESPACE) {
    errors.push(`Expected xmlns="${SCXML_NAMESPACE}", got "${model.xmlns}"`);
  }
  if (model.version !== '1.0') {
    errors.push(`Expected version="1.0", got "${model.version}"`);
  }
  if (!model.initial || !stateIds.has(model.initial)) {
    errors.push(`Initial state "${model.initial}" is not defined in <state> elements`);
  }

  for (const t of model.transitions) {
    if (!stateIds.has(t.target)) {
      errors.push(`Transition from "${t.source}" on "${t.event}" targets unknown state "${t.target}"`);
    }
    if (!t.event) {
      errors.push(`Transition from "${t.source}" to "${t.target}" is missing an event (OpenAPI operationId)`);
    } else if (!openApiOperationIds.has(t.event)) {
      errors.push(`Transition event "${t.event}" in state "${t.source}" does not match any OpenAPI operationId`);
    }
  }

  return errors;
}

export function main() {
  if (!fs.existsSync(defaultOpenApiFile)) {
    console.error(`Bundled OpenAPI spec not found: ${defaultOpenApiFile}. Run "npm run bundle:openapi" first.`);
    process.exit(1);
  }
  if (!fs.existsSync(defaultModelsDir)) {
    console.error(`Models directory not found: ${defaultModelsDir}`);
    process.exit(1);
  }

  const openApiDoc = YAML.parse(fs.readFileSync(defaultOpenApiFile, 'utf8'));
  const openApiOperationIds = extractOpenApiOperationIds(openApiDoc);
  const scxmlFiles = fs.readdirSync(defaultModelsDir)
    .filter(f => f.endsWith('.scxml'))
    .sort()
    .map(f => path.join(defaultModelsDir, f));

  if (scxmlFiles.length === 0) {
    console.error(`No SCXML model files found in ${defaultModelsDir}`);
    process.exit(1);
  }

  let hasErrors = false;
  for (const file of scxmlFiles) {
    const xml = fs.readFileSync(file, 'utf8');
    const model = parseScxml(xml);
    const errors = validateScxmlModel(model, openApiOperationIds);
    if (errors.length > 0) {
      hasErrors = true;
      console.error(`Validation failed for ${path.relative(rootDir, file)}:`);
      for (const err of errors) {
        console.error(`  - ${err}`);
      }
    }
  }

  if (hasErrors) {
    process.exit(1);
  }

  console.log(`All SCXML models (${scxmlFiles.length}) are valid and aligned with OpenAPI operationIds.`);
}

if (process.argv[1] && path.resolve(process.argv[1]) === import.meta.filename) {
  main();
}
