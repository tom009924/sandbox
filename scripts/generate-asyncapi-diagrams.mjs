import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import YAML from 'yaml';

const rootDir = path.resolve(import.meta.dirname, '..');
const defaultSpecPath = path.join(rootDir, 'docs', 'asyncapi.yaml');
const defaultOutputPath = path.join(rootDir, 'docs', 'asyncapi.md');

export function resolveRefName(ref) {
  if (typeof ref !== 'string') return '';
  const parts = ref.split('/');
  return parts[parts.length - 1] || ref;
}

export function generateFlowchart(spec) {
  const serviceName = spec.info?.title || 'Service';
  const servers = spec.servers || {};
  const channels = spec.channels || {};
  const operations = spec.operations || {};

  const serverEntries = Object.entries(servers);
  const protocolLabel = serverEntries.length > 0
    ? serverEntries.map(([name, s]) => `${name} (${s.protocol || 'async'})`).join(', ')
    : 'Broker';

  const lines = [
    '```mermaid',
    'flowchart LR',
    `    Producer["${serviceName}"]`,
  ];

  for (const [channelKey, channel] of Object.entries(channels)) {
    const address = channel.address || channelKey;
    lines.push(`    Channel_${channelKey}["Channel: ${channelKey}<br/>${address}<br/>[${protocolLabel}]"]`);
  }

  for (const [opKey, op] of Object.entries(operations)) {
    const channelKey = resolveRefName(op.channel?.$ref);
    const action = op.action || 'send';
    const messageNames = (op.messages || [])
      .map((m) => resolveRefName(m.$ref))
      .filter(Boolean)
      .join(', ');
    const label = messageNames ? `${opKey} (${action}: ${messageNames})` : `${opKey} (${action})`;

    if (action === 'receive') {
      lines.push(`    Channel_${channelKey} -->|${label}| Producer`);
    } else {
      lines.push(`    Producer -->|${label}| Channel_${channelKey}`);
    }
  }

  lines.push('```');
  return lines.join('\n');
}

export function generateMarkdown(spec) {
  const title = spec.info?.title || 'AsyncAPI Specification';
  const version = spec.info?.version || '1.0.0';
  const description = (spec.info?.description || '').trim();
  const channels = spec.channels || {};
  const operations = spec.operations || {};
  const schemas = spec.components?.schemas || {};

  const sections = [
    `# 非同期イベント仕様・可視化ダイアグラム (${title} v${version})`,
    '',
    '> **自動生成ドキュメント**: 本ドキュメントは `docs/asyncapi.yaml` からスクリプト（`npm run generate:asyncapi-diagrams`）により自動生成されています。手動編集は行わず、`docs/asyncapi.yaml` を直接更新してください。',
    '',
  ];

  if (description) {
    sections.push(description, '');
  }

  sections.push('## イベントフロー図', '', generateFlowchart(spec), '');

  sections.push('## チャネル & オペレーション一覧', '');
  sections.push('| Operation | Action | Channel | Address | Summary |');
  sections.push('| :--- | :--- | :--- | :--- | :--- |');
  for (const [opKey, op] of Object.entries(operations)) {
    const channelKey = resolveRefName(op.channel?.$ref);
    const address = channels[channelKey]?.address || '-';
    const summary = (op.summary || '-').replace(/\|/g, '\\|');
    sections.push(`| \`${opKey}\` | \`${op.action || '-'}\` | \`${channelKey}\` | \`${address}\` | ${summary} |`);
  }
  sections.push('');

  sections.push('## スキーマ一覧', '');
  for (const [schemaName, schema] of Object.entries(schemas)) {
    const requiredSet = new Set(schema.required || []);
    sections.push(`### \`${schemaName}\``, '');
    sections.push('| Property | Type | Format | Required | Nullable |');
    sections.push('| :--- | :--- | :--- | :---: | :---: |');
    for (const [propName, prop] of Object.entries(schema.properties || {})) {
      const oneOf = Array.isArray(prop.oneOf) ? prop.oneOf : null;
      const nonNullOneOf = oneOf ? oneOf.find((s) => s.type !== 'null') : null;
      const hasNullOneOf = oneOf ? oneOf.some((s) => s.type === 'null') : false;
      const isTypeArray = Array.isArray(prop.type);
      const hasNullType = isTypeArray ? prop.type.includes('null') : false;

      let refName;
      if (prop.$ref) {
        refName = `\`$ref: ${resolveRefName(prop.$ref)}\``;
      } else if (nonNullOneOf?.$ref) {
        refName = `\`$ref: ${resolveRefName(nonNullOneOf.$ref)}\``;
      } else if (isTypeArray) {
        const types = prop.type.filter((t) => t !== 'null');
        refName = `\`${types.join(' | ') || 'any'}\``;
      } else {
        refName = `\`${prop.type || nonNullOneOf?.type || 'any'}\``;
      }

      const format = prop.format ? `\`${prop.format}\`` : '-';
      const req = requiredSet.has(propName) ? 'Yes' : 'No';
      const nullable = prop.nullable || hasNullOneOf || hasNullType ? 'Yes' : 'No';
      sections.push(`| \`${propName}\` | ${refName} | ${format} | ${req} | ${nullable} |`);
    }
    sections.push('');
  }

  return `${sections.join('\n').trimEnd()}\n`;
}

export function run(argv = process.argv.slice(2)) {
  const isCheckMode = argv.includes('--check');
  const specRaw = fs.readFileSync(defaultSpecPath, 'utf8');
  const spec = YAML.parse(specRaw);
  const generated = generateMarkdown(spec);

  if (isCheckMode) {
    const current = fs.existsSync(defaultOutputPath)
      ? fs.readFileSync(defaultOutputPath, 'utf8')
      : '';
    if (current !== generated) {
      console.error('docs/asyncapi.md is out of date. Run `npm run generate:asyncapi-diagrams`.');
      process.exitCode = 1;
      return false;
    }
    console.log('docs/asyncapi.md is up to date.');
    return true;
  }

  fs.writeFileSync(defaultOutputPath, generated, 'utf8');
  console.log('Generated docs/asyncapi.md');
  return true;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  run();
}
