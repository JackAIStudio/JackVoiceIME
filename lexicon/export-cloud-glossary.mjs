/**
 * 把 assets 里的当前词库导出成火山 TOS 的 glossary.json。
 *
 * 用法：
 *   node lexicon/export-cloud-glossary.mjs <旧 glossary.json> <输出路径>
 *
 * 旧文件只用来保留条目的稳定 ID 和 isEnabled；没有旧文件时也能生成。
 * 输出的格式与 `jackvoice-glossary-v1` 一致。
 */

import { readFileSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { join } from 'node:path';

const ROOT = new URL('..', import.meta.url).pathname;
const ASSETS = join(ROOT, 'app', 'src', 'main', 'assets');
const oldPath = process.argv[2];
const outPath = process.argv[3] || join(ROOT, 'lexicon', 'glossary.json');

const hotwords = JSON.parse(readFileSync(join(ASSETS, 'hotwords.json'), 'utf8'));
const replacements = JSON.parse(readFileSync(join(ASSETS, 'replacements.json'), 'utf8'));

const old =
  oldPath && oldPath !== '-'
    ? JSON.parse(readFileSync(oldPath, 'utf8'))
    : { hotwords: [], replacements: [] };

const UUID_NAMESPACE = '6ba7b810-9dad-11d1-80b4-00c04fd430c8';

function uuidV5(name) {
  const ns = Buffer.from(UUID_NAMESPACE.replace(/-/g, ''), 'hex');
  const hash = createHash('sha1').update(ns).update(name, 'utf8').digest();
  hash[6] = (hash[6] & 0x0f) | 0x50;
  hash[8] = (hash[8] & 0x3f) | 0x80;
  const hex = hash.subarray(0, 16).toString('hex');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`.toUpperCase();
}

function normalizeHotword(value) {
  return [...value.trim()].filter((ch) => /\p{L}|\p{N}/u.test(ch)).join('');
}

const oldHotwords = new Map(
  (old.hotwords || []).map((item) => [normalizeHotword(item.term).toLowerCase(), item]),
);
const oldReplacements = new Map(
  (old.replacements || []).map((item) => [item.from.trim().toLowerCase(), item]),
);

const nextHotwords = [];
for (const raw of hotwords) {
  const term = normalizeHotword(raw);
  if (!term) continue;
  const key = term.toLowerCase();
  const previous = oldHotwords.get(key);
  nextHotwords.push({
    id: previous?.id || uuidV5(`jackvoice:hotword:${key}`),
    term,
    isEnabled: previous?.isEnabled ?? true,
  });
}

const nextReplacements = [];
for (const rule of replacements) {
  const from = rule.from.trim();
  const to = rule.to.trim();
  if (!from || !to) continue;
  const key = from.toLowerCase();
  const previous = oldReplacements.get(key);
  nextReplacements.push({
    id: previous?.id || uuidV5(`jackvoice:replacement:${key}`),
    from,
    to,
    isEnabled: previous?.isEnabled ?? true,
  });
}

const output = {
  format: 'jackvoice-glossary-v1',
  version: 1,
  updatedAt: new Date().toISOString(),
  updatedBy: 'JackVoice',
  hotwords: nextHotwords,
  replacements: nextReplacements,
};

writeFileSync(outPath, `${JSON.stringify(output, null, 2)}\n`, 'utf8');
console.log(
  `✅ 已生成 ${outPath}：热词 ${nextHotwords.length} 条，替换词 ${nextReplacements.length} 条`,
);
