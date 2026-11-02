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

function extractStatusCode(successCriteria) {
  if (!Array.isArray(successCriteria)) return '200 OK';
  for (const item of successCriteria) {
    if (item.condition) {
      const match = item.condition.match(/\$statusCode\s*==\s*(\d+)/);
      if (match) {
        const code = match[1];
        const statusText = {
          '200': '200 OK',
          '201': '201 Created',
          '204': '204 No Content',
          '400': '400 Bad Request',
          '401': '401 Unauthorized',
          '403': '403 Forbidden',
          '404': '404 Not Found',
          '409': '409 Conflict',
        }[code] || code;
        return statusText;
      }
    }
  }
  return '200 OK';
}

function cleanOperationId(opId) {
  if (!opId) return '';
  return opId.replace(/^\$sourceDescriptions\.[^.]+\./, '');
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
    const op = cleanOperationId(step.operationId);
    const desc = step.description ? `: ${step.description}` : '';
    lines.push(`    Client->>API: ${step.stepId} (${op})${desc}`);

    const status = extractStatusCode(step.successCriteria);
    const outputs = step.outputs ? Object.keys(step.outputs).join(', ') : '';
    const resText = outputs ? `${status} [outputs: ${outputs}]` : status;
    lines.push(`    API-->>Client: ${resText}`);
  }

  lines.push('```');
  return lines.join('\n');
}

function generateFlowchart(workflow) {
  const lines = [
    '```mermaid',
    'flowchart TD',
    `    Start(["開始: ${workflow.workflowId}"])`,
  ];

  let prevNode = 'Start';
  (workflow.steps || []).forEach((step, index) => {
    const nodeId = `Step${index + 1}`;
    const op = cleanOperationId(step.operationId);
    const desc = step.description ? `<br/><i>${step.description}</i>` : '';
    const status = extractStatusCode(step.successCriteria);
    const label = `<b>${step.stepId}</b> (${op})${desc}<br/><code>${status}</code>`;
    lines.push(`    ${nodeId}["${label}"]`);
    lines.push(`    ${prevNode} --> ${nodeId}`);
    prevNode = nodeId;
  });

  lines.push(`    End(["完了: ${workflow.workflowId}"])`);
  lines.push(`    ${prevNode} --> End`);
  lines.push('```');
  return lines.join('\n');
}

function generateStepsTable(workflow) {
  const lines = [
    '| # | Step ID | Operation | 概要 | 期待ステータス | 出力 (Outputs) |',
    '| :-: | :--- | :--- | :--- | :---: | :--- |',
  ];

  (workflow.steps || []).forEach((step, index) => {
    const op = `\`${cleanOperationId(step.operationId)}\``;
    const desc = step.description || '-';
    const status = `\`${extractStatusCode(step.successCriteria)}\``;
    const outputs = step.outputs ? Object.keys(step.outputs).map(k => `\`${k}\``).join(', ') : '-';
    lines.push(`| ${index + 1} | \`${step.stepId}\` | ${op} | ${desc} | ${status} | ${outputs} |`);
  });

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

      sections.push('#### シーケンス図 (Sequence Diagram)');
      sections.push(generateSequenceDiagram(wf));
      sections.push('');

      sections.push('#### フローチャート (Execution Flowchart)');
      sections.push(generateFlowchart(wf));
      sections.push('');

      sections.push('#### ステップ詳細一覧');
      sections.push(generateStepsTable(wf));
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
