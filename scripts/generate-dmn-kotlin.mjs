#!/usr/bin/env node
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseDmnModel } from './check-dmn-model.mjs';

function toPascalCase(value) {
  return value
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .split(/[^A-Za-z0-9]+/)
    .filter(Boolean)
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join('');
}

function kotlinTypeFor(typeRef) {
  if (typeRef === 'boolean') return 'Boolean';
  if (typeRef === 'integer') return 'Int';
  if (typeRef === 'string') return 'String';
  throw new Error(`Unsupported DMN typeRef: ${typeRef}`);
}

function kotlinLiteralFor(entry, typeRef) {
  if (typeRef === 'boolean' || typeRef === 'integer' || typeRef === 'string') {
    return entry;
  }
  throw new Error(`Unsupported DMN typeRef: ${typeRef}`);
}

function renderCondition(input, entry) {
  if (entry === '-') return null;
  if (input.typeRef === 'boolean') {
    return entry === 'true' ? `input.${input.label}` : `!input.${input.label}`;
  }
  return `input.${input.label} == ${kotlinLiteralFor(entry, input.typeRef)}`;
}

export function generateDmnKotlin(model) {
  const lines = [
    '// AUTO-GENERATED from docs/models/todo-decisions.dmn by scripts/generate-dmn-kotlin.mjs',
    '// Do not edit manually. Update docs/models/todo-decisions.dmn and run `npm run generate:dmn-kotlin`.',
    '@file:Suppress("MagicNumber", "MaxLineLength")',
    '',
    'package com.example.todo.domain.decision',
    '',
  ];

  for (const decision of model.decisions) {
    const baseName = toPascalCase(decision.id);
    const inputClass = `${baseName}Input`;
    const resultClass = `${baseName}Result`;

    lines.push(`data class ${inputClass}(`);
    decision.table.inputs.forEach((input) => {
      lines.push(`    val ${input.label}: ${kotlinTypeFor(input.typeRef)},`);
    });
    lines.push(')', '');

    lines.push(`data class ${resultClass}(`);
    lines.push('    val matchedRuleId: String,');
    decision.table.outputs.forEach((output) => {
      lines.push(`    val ${output.name}: ${kotlinTypeFor(output.typeRef)},`);
    });
    lines.push(')', '');
  }

  lines.push('object TodoDecisionModel {');

  model.decisions.forEach((decision, decisionIndex) => {
    const baseName = toPascalCase(decision.id);
    const inputClass = `${baseName}Input`;
    const resultClass = `${baseName}Result`;
    const fnName = `evaluate${baseName}`;

    if (decisionIndex > 0) {
      lines.push('');
    }
    lines.push(`    fun ${fnName}(input: ${inputClass}): ${resultClass} = when {`);

    decision.table.rules.forEach((rule, ruleIndex) => {
      const isLast = ruleIndex === decision.table.rules.length - 1;
      const conditions = decision.table.inputs
        .map((input, idx) => renderCondition(input, rule.inputEntries[idx]))
        .filter(Boolean);
      const outputArgs = [
        `matchedRuleId = "${rule.id}"`,
        ...decision.table.outputs.map(
          (output, idx) => `${output.name} = ${kotlinLiteralFor(rule.outputEntries[idx], output.typeRef)}`,
        ),
      ].join(', ');

      if (isLast && conditions.length === 0) {
        lines.push(`        else -> ${resultClass}(${outputArgs})`);
      } else {
        const condExpr = conditions.length > 0 ? conditions.join(' && ') : 'true';
        lines.push(`        ${condExpr} -> ${resultClass}(${outputArgs})`);
      }
    });

    lines.push('    }');
  });

  lines.push('}', '');
  return lines.join('\n');
}

function main() {
  const __dirname = path.dirname(fileURLToPath(import.meta.url));
  const repoRoot = path.resolve(__dirname, '..');
  const dmnPath = path.join(repoRoot, 'docs/models/todo-decisions.dmn');
  const outPath = path.join(
    repoRoot,
    'services/todo-service/build/generated/dmn/src/main/kotlin/com/example/todo/domain/decision/TodoDecisionModel.kt',
  );

  const model = parseDmnModel(fs.readFileSync(dmnPath, 'utf8'));
  const kotlinSource = generateDmnKotlin(model);

  fs.mkdirSync(path.dirname(outPath), { recursive: true });
  fs.writeFileSync(outPath, kotlinSource, 'utf8');
  console.log(`Generated ${path.relative(repoRoot, outPath)}`);
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main();
}
