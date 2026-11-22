#!/usr/bin/env node
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import yaml from 'yaml';

const DMN_NAMESPACE = 'https://www.omg.org/spec/DMN/20191111/MODEL/';
const ALLOWED_HIT_POLICIES = new Set(['FIRST', 'UNIQUE', 'PRIORITY', 'ANY', 'COLLECT']);
const ALLOWED_TYPE_REFS = new Set(['boolean', 'string', 'integer']);

function decodeXml(value) {
  return value
    .replaceAll('&lt;', '<')
    .replaceAll('&gt;', '>')
    .replaceAll('&quot;', '"')
    .replaceAll('&apos;', "'")
    .replaceAll('&amp;', '&');
}

function parseAttributes(attrText) {
  const attrs = {};
  const attrRegex = /([A-Za-z_][\w:.-]*)\s*=\s*"([^"]*)"/g;
  let match;
  while ((match = attrRegex.exec(attrText)) !== null) {
    attrs[match[1]] = decodeXml(match[2]);
  }
  return attrs;
}

function extractTagText(xml, tagName) {
  const match = xml.match(new RegExp(`<${tagName}\\b[^>]*>([\\s\\S]*?)<\\/${tagName}>`));
  return match ? decodeXml(match[1].trim()) : '';
}

function extractAllBlocks(xml, tagName) {
  const blocks = [];
  const regex = new RegExp(`<${tagName}\\b([^>]*)>([\\s\\S]*?)<\\/${tagName}>`, 'g');
  let match;
  while ((match = regex.exec(xml)) !== null) {
    blocks.push({
      attrs: parseAttributes(match[1]),
      body: match[2],
    });
  }
  return blocks;
}

function extractSelfClosingOrBlocks(xml, tagName) {
  const items = [];
  const regex = new RegExp(`<${tagName}\\b([^>]*?)(?:\\/>|>([\\s\\S]*?)<\\/${tagName}>)`, 'g');
  let match;
  while ((match = regex.exec(xml)) !== null) {
    items.push({
      attrs: parseAttributes(match[1]),
      body: match[2] ?? '',
    });
  }
  return items;
}

export function parseDmnModel(xmlContent) {
  const rootMatch = xmlContent.match(/<definitions\b([^>]*)>/);
  if (!rootMatch) {
    throw new Error('DMN XML must contain a <definitions> root element');
  }
  const rootAttrs = parseAttributes(rootMatch[1]);
  const decisions = extractAllBlocks(xmlContent, 'decision').map((decisionBlock) => {
    const operationsText = extractTagText(decisionBlock.body, 'todo:operations');
    const operations = operationsText
      ? operationsText.split(',').map((op) => op.trim()).filter(Boolean)
      : [];
    const tableBlocks = extractAllBlocks(decisionBlock.body, 'decisionTable');
    if (tableBlocks.length !== 1) {
      throw new Error(
        `Decision "${decisionBlock.attrs.id ?? 'unknown'}" must contain exactly one <decisionTable>`,
      );
    }
    const tableBlock = tableBlocks[0];
    const inputs = extractAllBlocks(tableBlock.body, 'input').map((inputBlock) => {
      const exprBlocks = extractAllBlocks(inputBlock.body, 'inputExpression');
      const exprBlock = exprBlocks[0] ?? { attrs: {}, body: '' };
      return {
        id: inputBlock.attrs.id ?? '',
        label: inputBlock.attrs.label ?? '',
        typeRef: exprBlock.attrs.typeRef ?? '',
        expression: extractTagText(exprBlock.body, 'text'),
      };
    });
    const outputs = extractSelfClosingOrBlocks(tableBlock.body, 'output').map((outputBlock) => ({
      id: outputBlock.attrs.id ?? '',
      label: outputBlock.attrs.label ?? outputBlock.attrs.name ?? '',
      name: outputBlock.attrs.name ?? '',
      typeRef: outputBlock.attrs.typeRef ?? '',
    }));
    const rules = extractAllBlocks(tableBlock.body, 'rule').map((ruleBlock) => ({
      id: ruleBlock.attrs.id ?? '',
      description: extractTagText(ruleBlock.body, 'description'),
      inputEntries: extractAllBlocks(ruleBlock.body, 'inputEntry').map((entry) =>
        extractTagText(entry.body, 'text'),
      ),
      outputEntries: extractAllBlocks(ruleBlock.body, 'outputEntry').map((entry) =>
        extractTagText(entry.body, 'text'),
      ),
    }));

    return {
      id: decisionBlock.attrs.id ?? '',
      name: decisionBlock.attrs.name ?? '',
      description: extractTagText(decisionBlock.body, 'description'),
      operations,
      table: {
        id: tableBlock.attrs.id ?? '',
        hitPolicy: tableBlock.attrs.hitPolicy ?? 'UNIQUE',
        inputs,
        outputs,
        rules,
      },
    };
  });

  return {
    id: rootAttrs.id ?? '',
    name: rootAttrs.name ?? '',
    namespace: rootAttrs.namespace ?? '',
    xmlns: rootAttrs.xmlns ?? '',
    decisions,
  };
}

export function collectOpenApiOperationIds(openApiDoc) {
  const operationIds = new Set();
  const paths = openApiDoc?.paths ?? {};
  for (const pathItem of Object.values(paths)) {
    if (!pathItem || typeof pathItem !== 'object') continue;
    for (const operation of Object.values(pathItem)) {
      if (operation && typeof operation === 'object' && typeof operation.operationId === 'string') {
        operationIds.add(operation.operationId);
      }
    }
  }
  return operationIds;
}

function isValidEntryForType(entry, typeRef, allowWildcard) {
  if (allowWildcard && entry === '-') return true;
  if (typeRef === 'boolean') return entry === 'true' || entry === 'false';
  if (typeRef === 'integer') return /^-?\d+$/.test(entry);
  if (typeRef === 'string') return /^"[^"]*"$/.test(entry);
  return false;
}

export function validateDmnModel(model, openApiOperationIds) {
  const errors = [];

  if (model.xmlns !== DMN_NAMESPACE) {
    errors.push(`DMN xmlns must be "${DMN_NAMESPACE}", got "${model.xmlns}"`);
  }
  if (!model.id) {
    errors.push('DMN <definitions> must define an id attribute');
  }
  if (model.decisions.length === 0) {
    errors.push('DMN model must define at least one <decision>');
  }

  const decisionIds = new Set();
  for (const decision of model.decisions) {
    if (!decision.id) {
      errors.push('Every <decision> must have an id');
      continue;
    }
    if (decisionIds.has(decision.id)) {
      errors.push(`Duplicate decision id: "${decision.id}"`);
    }
    decisionIds.add(decision.id);

    if (decision.operations.length === 0) {
      errors.push(`Decision "${decision.id}" must declare at least one <todo:operations> operationId`);
    }
    for (const opId of decision.operations) {
      if (!openApiOperationIds.has(opId)) {
        errors.push(`Decision "${decision.id}" references unknown OpenAPI operationId "${opId}"`);
      }
    }

    const { table } = decision;
    if (!ALLOWED_HIT_POLICIES.has(table.hitPolicy)) {
      errors.push(`Decision "${decision.id}" has unsupported hitPolicy "${table.hitPolicy}"`);
    }
    if (table.inputs.length === 0) {
      errors.push(`Decision "${decision.id}" must define at least one input`);
    }
    if (table.outputs.length === 0) {
      errors.push(`Decision "${decision.id}" must define at least one output`);
    }
    for (const input of table.inputs) {
      if (!ALLOWED_TYPE_REFS.has(input.typeRef)) {
        errors.push(`Decision "${decision.id}" input "${input.label}" has unsupported typeRef "${input.typeRef}"`);
      }
    }
    for (const output of table.outputs) {
      if (!ALLOWED_TYPE_REFS.has(output.typeRef)) {
        errors.push(`Decision "${decision.id}" output "${output.name}" has unsupported typeRef "${output.typeRef}"`);
      }
    }
    if (table.rules.length === 0) {
      errors.push(`Decision "${decision.id}" must define at least one rule`);
      continue;
    }

    const ruleIds = new Set();
    for (const rule of table.rules) {
      if (!rule.id) {
        errors.push(`Decision "${decision.id}" contains a rule without an id`);
      } else if (ruleIds.has(rule.id)) {
        errors.push(`Decision "${decision.id}" has duplicate rule id "${rule.id}"`);
      } else {
        ruleIds.add(rule.id);
      }

      if (rule.inputEntries.length !== table.inputs.length) {
        errors.push(
          `Rule "${rule.id}" in "${decision.id}" has ${rule.inputEntries.length} inputEntry elements, expected ${table.inputs.length}`,
        );
      } else {
        rule.inputEntries.forEach((entry, idx) => {
          const input = table.inputs[idx];
          if (!isValidEntryForType(entry, input.typeRef, true)) {
            errors.push(
              `Rule "${rule.id}" input "${input.label}" has invalid FEEL literal "${entry}" for type "${input.typeRef}"`,
            );
          }
        });
      }

      if (rule.outputEntries.length !== table.outputs.length) {
        errors.push(
          `Rule "${rule.id}" in "${decision.id}" has ${rule.outputEntries.length} outputEntry elements, expected ${table.outputs.length}`,
        );
      } else {
        rule.outputEntries.forEach((entry, idx) => {
          const output = table.outputs[idx];
          if (!isValidEntryForType(entry, output.typeRef, false)) {
            errors.push(
              `Rule "${rule.id}" output "${output.name}" has invalid FEEL literal "${entry}" for type "${output.typeRef}"`,
            );
          }
        });
      }
    }

    if (table.hitPolicy === 'FIRST') {
      const lastRule = table.rules[table.rules.length - 1];
      const isCatchAll = lastRule.inputEntries.every((entry) => entry === '-');
      if (!isCatchAll) {
        errors.push(
          `Decision "${decision.id}" uses FIRST hitPolicy and must end with a catch-all ("-") default rule`,
        );
      }
    }
  }

  return errors;
}

function main() {
  const __dirname = path.dirname(fileURLToPath(import.meta.url));
  const repoRoot = path.resolve(__dirname, '..');
  const dmnPath = path.join(repoRoot, 'docs/models/todo-decisions.dmn');
  const openApiPath = path.join(repoRoot, 'docs/dist/openapi.admin.yaml');

  const xmlContent = fs.readFileSync(dmnPath, 'utf8');
  const openApiDoc = yaml.parse(fs.readFileSync(openApiPath, 'utf8'));
  const model = parseDmnModel(xmlContent);
  const operationIds = collectOpenApiOperationIds(openApiDoc);
  const errors = validateDmnModel(model, operationIds);

  if (errors.length > 0) {
    console.error('DMN model validation failed:');
    for (const err of errors) {
      console.error(`  - ${err}`);
    }
    process.exit(1);
  }

  const totalRules = model.decisions.reduce((sum, d) => sum + d.table.rules.length, 0);
  console.log(
    `DMN model valid: ${model.decisions.length} decisions, ${totalRules} rules verified against OpenAPI.`,
  );
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main();
}
