/**
 * 词库单一数据源 → Android assets。
 *
 *   读  lexicon/src/hotwords.ts      → 写 app/src/main/assets/hotwords.json
 *   读  lexicon/src/replacements.ts  → 写 app/src/main/assets/replacements.json
 *
 * 手动跑：node lexicon/export-assets.mjs
 *
 * 这两个 .ts 是**唯一源头**，`assets/*.json` 都是生成物。
 * 也可以从外部共享数据目录重新生成：node lexicon/import-desktop.mjs。
 * 改完 .ts 再跑一次本脚本。
 */

import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const LEXICON_ROOT = HERE;
const REPO_ROOT = join(LEXICON_ROOT, '..');
const OUT_DIR = join(REPO_ROOT, 'app', 'src', 'main', 'assets');

const DATA_DIR = join(LEXICON_ROOT, 'src');

function readSource(file) {
  try {
    return readFileSync(join(DATA_DIR, file), 'utf8');
  } catch (e) {
    console.error(`❌ 读不到 ${file}: ${e.message}`);
    process.exit(1);
  }
}

/** 从 DEFAULT_HOTWORDS 数组里取字符串 */
function extractHotwords(src) {
  const key = 'DEFAULT_HOTWORDS';
  const at = src.indexOf(key);
  if (at < 0) throw new Error('源码里找不到 DEFAULT_HOTWORDS');
  const start = src.indexOf('[', at);
  const end = src.lastIndexOf(']');
  if (start < 0 || end < 0) throw new Error('DEFAULT_HOTWORDS 数组格式异常');
  const body = src.slice(start + 1, end);
  return [...body.matchAll(/"([^"]*)"/g)].map((m) => m[1]);
}

/** 从 DEFAULT_REPLACEMENTS 里取 { from, to } */
function extractRules(src) {
  const out = [];
  const re = /\{\s*from:\s*"((?:[^"\\]|\\.)*)"\s*,\s*to:\s*"((?:[^"\\]|\\.)*)"\s*\}/g;
  for (const m of src.matchAll(re)) {
    out.push({ from: m[1], to: m[2] });
  }
  return out;
}

const hotwords = extractHotwords(readSource('hotwords.ts'));
const rules = extractRules(readSource('replacements.ts'));

if (hotwords.length === 0) {
  console.error('❌ 热词解析出 0 条，数据源格式可能变了，中止');
  process.exit(1);
}
if (rules.length === 0) {
  console.error('❌ 替换规则解析出 0 条，数据源格式可能变了，中止');
  process.exit(1);
}

mkdirSync(OUT_DIR, { recursive: true });

// 逐项展开输出。注意两点，都是为了与仓库既有格式保持一致：
//   1. JS 的 JSON.stringify(x, null, 0) 会输出**单行**，需要手工拼换行
//   2. 每个元素内部也按仓库格式展开（对象键各自一行）
const jsonArray = (arr) => '[\n' + arr.map((item) => JSON.stringify(item, null, 0)).join(',\n') + '\n]';

/** 对象按仓库格式展开：{\n"from": "...",\n"to": "..."\n} */
const ruleLiteral = (r) =>
  '{\n' + `"from": ${JSON.stringify(r.from)},\n"to": ${JSON.stringify(r.to)}\n}`;

writeFileSync(join(OUT_DIR, 'hotwords.json'), jsonArray(hotwords), 'utf8');
writeFileSync(
  join(OUT_DIR, 'replacements.json'),
  '[\n' + rules.map(ruleLiteral).join(',\n') + '\n]',
  'utf8'
);

console.log(`✅ 词库已同步到 assets：热词 ${hotwords.length} 条，替换规则 ${rules.length} 条`);
