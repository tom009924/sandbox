#!/usr/bin/env node
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseDmnModel } from './check-dmn-model.mjs';

function escapePipe(value) {
  return String(value).replaceAll('|', '\\|');
}

export function renderDmnMarkdown(model) {
  const lines = [
    '# OMG DMN 1.3 ディシジョンモデル',
    '',
    '<!-- AUTO-GENERATED from docs/models/todo-decisions.dmn by scripts/generate-dmn-diagrams.mjs -->',
    '',
    '## Decision Requirements Diagram (DRD)',
    '',
    '```mermaid',
    'flowchart LR',
  ];

  const uniqueOperations = new Set();
  for (const decision of model.decisions) {
    for (const op of decision.operations) {
      uniqueOperations.add(op);
    }
  }

  for (const op of Array.from(uniqueOperations).sort()) {
    lines.push(`  op_${op}(["OpenAPI: ${op}"])`);
  }
  for (const decision of model.decisions) {
    lines.push(`  dec_${decision.id}["${decision.name} (${decision.table.hitPolicy})"]`);
    for (const op of decision.operations) {
      lines.push(`  op_${op} --> dec_${decision.id}`);
    }
  }
  lines.push('```', '');

  for (const decision of model.decisions) {
    lines.push(`## ${decision.name} (\`${decision.id}\`)`, '');
    if (decision.description) {
      lines.push(decision.description, '');
    }
    lines.push(
      `- **Hit Policy**: \`${decision.table.hitPolicy}\``,
      `- **OpenAPI operations**: ${decision.operations.map((op) => `\`${op}\``).join(', ')}`,
      '',
    );

    const headers = [
      '#',
      'Rule ID',
      ...decision.table.inputs.map((input) => `${input.label} (${input.typeRef})`),
      ...decision.table.outputs.map((output) => `**${output.name}** (${output.typeRef})`),
      'Description',
    ];
    lines.push(`| ${headers.map(escapePipe).join(' | ')} |`);
    lines.push(`| ${headers.map(() => '---').join(' | ')} |`);

    decision.table.rules.forEach((rule, index) => {
      const cells = [
        String(index + 1),
        `\`${rule.id}\``,
        ...rule.inputEntries.map((entry) => `\`${entry}\``),
        ...rule.outputEntries.map((entry) => `**\`${entry}\`**`),
        rule.description || '',
      ];
      lines.push(`| ${cells.map(escapePipe).join(' | ')} |`);
    });
    lines.push('');
  }

  return `${lines.join('\n')}\n`;
}

function main() {
  const __dirname = path.dirname(fileURLToPath(import.meta.url));
  const repoRoot = path.resolve(__dirname, '..');
  const dmnPath = path.join(repoRoot, 'docs/models/todo-decisions.dmn');
  const outPath = path.join(repoRoot, 'docs/models/DMN.md');
  const checkOnly = process.argv.includes('--check');

  const model = parseDmnModel(fs.readFileSync(dmnPath, 'utf8'));
  const rendered = renderDmnMarkdown(model);

  if (checkOnly) {
    const existing = fs.existsSync(outPath) ? fs.readFileSync(outPath, 'utf8') : '';
    if (existing !== rendered) {
      console.error('docs/models/DMN.md is out of date. Run: npm run generate:dmn-diagrams');
      process.exit(1);
    }
    console.log('docs/models/DMN.md is up to date.');
    return;
  }

  fs.mkdirSync(path.dirname(outPath), { recursive: true });
  fs.writeFileSync(outPath, rendered, 'utf8');
  console.log(`Generated ${path.relative(repoRoot, outPath)}`);
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main();
}
