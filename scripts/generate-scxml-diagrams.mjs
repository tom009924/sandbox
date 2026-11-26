import fs from 'node:fs';
import path from 'node:path';
import { parseScxml } from './check-scxml-model.mjs';

const rootDir = path.resolve(import.meta.dirname, '..');
const modelsDir = path.join(rootDir, 'docs', 'models');
const outputFile = path.join(modelsDir, 'SCXML.md');

const isCheckMode = process.argv.includes('--check');

export function generateStateDiagram(model) {
  const lines = [
    '```mermaid',
    'stateDiagram-v2',
    `    [*] --> ${model.initial}`,
  ];

  for (const t of model.transitions) {
    const label = t.cond ? `${t.event} [${t.cond}]` : t.event;
    lines.push(`    ${t.source} --> ${t.target}: ${label}`);
  }

  for (const s of model.states) {
    if (s.isFinal) {
      lines.push(`    ${s.id} --> [*]`);
    }
  }

  lines.push('```');
  return lines.join('\n');
}

export function generateMarkdown(scxmlFiles) {
  const sections = [
    '# モデルベースドテスト状態遷移仕様 (W3C SCXML)',
    '',
    '> **自動生成ドキュメント**: 本ドキュメントは `docs/models/*.scxml` からスクリプト（`npm run generate:scxml-diagrams`）により自動生成されています。手動編集は行わず、SCXML ファイルを直接更新してください。',
    '',
  ];

  for (const file of scxmlFiles) {
    const xml = fs.readFileSync(file, 'utf8');
    const model = parseScxml(xml);
    const relPath = path.relative(modelsDir, file);
    const uniqueEvents = [...new Set(model.transitions.map(t => t.event).filter(Boolean))].sort();

    sections.push(`## ${model.name || path.basename(file, '.scxml')} (\`${relPath}\`)`);
    sections.push('');
    sections.push(`- **初期状態**: \`${model.initial}\``);
    sections.push(`- **対象 OpenAPI \`operationId\` (${uniqueEvents.length}件)**: ${uniqueEvents.map(e => `\`${e}\``).join(', ')}`);
    sections.push('');
    sections.push(generateStateDiagram(model));
    sections.push('');
  }

  return sections.join('\n');
}

export function main() {
  const files = fs.readdirSync(modelsDir)
    .filter(f => f.endsWith('.scxml'))
    .sort()
    .map(f => path.join(modelsDir, f));

  if (files.length === 0) {
    console.error('No SCXML files found in', modelsDir);
    process.exit(1);
  }

  const generated = generateMarkdown(files);

  if (isCheckMode) {
    if (!fs.existsSync(outputFile)) {
      console.error(`Error: Output file ${outputFile} does not exist. Run "npm run generate:scxml-diagrams" first.`);
      process.exit(1);
    }
    const current = fs.readFileSync(outputFile, 'utf8');
    if (current !== generated) {
      console.error(`Error: SCXML model diagrams in ${outputFile} are out of date. Run "npm run generate:scxml-diagrams".`);
      process.exit(1);
    }
    console.log('✓ SCXML model diagrams are up to date.');
  } else {
    fs.writeFileSync(outputFile, generated, 'utf8');
    console.log(`✓ Successfully generated SCXML model diagrams at ${path.relative(rootDir, outputFile)}`);
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === import.meta.filename) {
  main();
}
