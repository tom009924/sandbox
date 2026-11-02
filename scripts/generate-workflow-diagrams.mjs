import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import yamlModule from '@stoplight/yaml';

const { parse } = yamlModule;

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);
const rootDir = path.resolve(__dirname, '..');
const workflowsDir = path.join(rootDir, 'docs', 'workflows');
const outputFile = path.join(workflowsDir, 'README.md');

const isCheckMode = process.argv.includes('--check');

function extractExpectedStatus(successCriteria) {
  if (!Array.isArray(successCriteria)) return '200';
  for (const item of successCriteria) {
    const match = item?.condition?.match(/\$statusCode\s*==\s*(\d+)/);
    if (match) return match[1];
  }
  return '200';
}

function generateSequenceDiagram(workflow) {
  const lines = [
    '```mermaid',
    'sequenceDiagram',
    '    autonumber',
    '    actor Client as Client / Runner',
    '    participant API as Todo API',
  ];

  for (const step of workflow.steps || []) {
    const desc = step.description ? `: ${step.description}` : '';
    lines.push(`    Client->>API: ${step.stepId}${desc}`);

    const status = extractExpectedStatus(step.successCriteria);
    const outputs = step.outputs ? Object.keys(step.outputs).join(', ') : '';
    const resText = outputs ? `${status} (outputs: ${outputs})` : status;
    lines.push(`    API-->>Client: ${resText}`);
  }

  lines.push('```');
  return lines.join('\n');
}

function generateMarkdown(arazzoFiles) {
  const sections = [
    '# E2E ワークフロー仕様・可視化ダイアグラム (Arazzo Workflows)',
    '',
    '> **自動生成ドキュメント**: 本ドキュメントは `docs/workflows/*.arazzo.yaml` からスクリプト（`npm run generate:diagrams`）により自動生成されています。手動編集は行わず、Arazzo ファイルを直接更新してください。',
    '',
    '> **Arazzo UI でのインタラクティブ閲覧**: `npm run arazzo:ui` を実行すると、ブラウザ上でステップ詳細やデータフローをインタラクティブに確認できます。',
    '',
  ];

  for (const file of arazzoFiles) {
    const content = fs.readFileSync(file, 'utf8');
    const doc = parse(content);

    sections.push(`## ${doc.info?.title || 'API Workflows'}`);
    if (doc.info?.description) {
      sections.push('');
      sections.push(doc.info.description);
    }
    sections.push('');

    for (const wf of doc.workflows || []) {
      sections.push(`### ワークフロー: \`${wf.workflowId}\``);
      if (wf.summary) sections.push(`**${wf.summary}**\n`);
      if (wf.description) sections.push(`${wf.description.trim()}\n`);

      sections.push(generateSequenceDiagram(wf));
      sections.push('');
      sections.push('---');
      sections.push('');
    }
  }

  return sections.join('\n');
}

function main() {
  const files = fs.readdirSync(workflowsDir)
    .filter(f => f.endsWith('.arazzo.yaml') || f.endsWith('.arazzo.yml'))
    .map(f => path.join(workflowsDir, f));

  if (files.length === 0) {
    console.error('No Arazzo files found in', workflowsDir);
    process.exit(1);
  }

  const generatedMarkdown = generateMarkdown(files);

  if (isCheckMode) {
    if (!fs.existsSync(outputFile)) {
      console.error(`Error: Output file ${outputFile} does not exist. Run "npm run generate:diagrams" first.`);
      process.exit(1);
    }
    const current = fs.readFileSync(outputFile, 'utf8');
    if (current !== generatedMarkdown) {
      console.error(`Error: Workflow diagrams in ${outputFile} are out of date. Run "npm run generate:diagrams".`);
      process.exit(1);
    }
    console.log('✓ Workflow diagrams are up to date.');
  } else {
    fs.writeFileSync(outputFile, generatedMarkdown, 'utf8');
    console.log(`✓ Successfully generated workflow diagrams at ${path.relative(rootDir, outputFile)}`);
  }
}

main();
