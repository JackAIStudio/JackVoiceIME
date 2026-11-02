/**
 * 从外部共享数据目录导入热词与替换词。
 *
 *   读  $JACKVOICE_SHARED_DATA_DIR/hotwords.json
 *   读  $JACKVOICE_SHARED_DATA_DIR/replacements.json
 *   写  lexicon/src/hotwords.ts
 *   写  lexicon/src/replacements.ts
 *
 * 之后仍需跑一次 `node lexicon/export-assets.mjs`，把 .ts 词库导出到
 * app/src/main/assets。
 *
 * 数据目录由 JACKVOICE_SHARED_DATA_DIR 指定；未设置时取 macOS 默认位置。
 */

import { readFileSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';

const DATA_DIR =
  process.env.JACKVOICE_SHARED_DATA_DIR ||
  join(homedir(), 'Library', 'Application Support', 'com.jackvoice.shared');
const DISPLAY_DIR = DATA_DIR.startsWith(homedir())
  ? DATA_DIR.replace(homedir(), '~')
  : DATA_DIR;

function readJson(name) {
  const path = join(DATA_DIR, name);
  try {
    return { path, value: JSON.parse(readFileSync(path, 'utf8')) };
  } catch (error) {
    console.error(`❌ 读不到 ${path}: ${error.message}`);
    process.exit(1);
  }
}

const hotwordsSource = readJson('hotwords.json');
const replacementsSource = readJson('replacements.json');

if (!Array.isArray(hotwordsSource.value)) {
  console.error(`❌ ${hotwordsSource.path} 不是字符串数组`);
  process.exit(1);
}
if (!Array.isArray(replacementsSource.value)) {
  console.error(`❌ ${replacementsSource.path} 不是数组`);
  process.exit(1);
}

const hotwords = hotwordsSource.value.filter((word) => typeof word === 'string');
const replacements = replacementsSource.value.map((rule) => {
  if (typeof rule?.from !== 'string' || typeof rule?.to !== 'string') {
    console.error(`❌ 替换词格式异常：${JSON.stringify(rule)}`);
    process.exit(1);
  }
  return { from: rule.from, to: rule.to };
});

const hotwordLines = hotwords.map((word) => `  ${JSON.stringify(word)},`).join('\n');
const replacementLines = replacements
  .map((rule) => `  { from: ${JSON.stringify(rule.from)}, to: ${JSON.stringify(rule.to)} },`)
  .join('\n');

writeFileSync(
  new URL('./src/hotwords.ts', import.meta.url),
  `// 从 JackVoice 桌面版 hotwords.json 导入（${hotwords.length} 条）
// 源: ${join(DISPLAY_DIR, 'hotwords.json')}
// 重新生成: node lexicon/import-desktop.mjs && node lexicon/export-assets.mjs

export const DEFAULT_HOTWORDS: string[] = [
${hotwordLines}
];
`,
  'utf8',
);

writeFileSync(
  new URL('./src/replacements.ts', import.meta.url),
  `// 从 JackVoice 桌面版 replacements.json 导入（${replacements.length} 条）
// 源: ${join(DISPLAY_DIR, 'replacements.json')}
// 语义: from → to。from==to 视为「短语锁」，保护该写法不被其它规则误伤。
// 重新生成: node lexicon/import-desktop.mjs && node lexicon/export-assets.mjs

export interface ReplacementRule {
  from: string;
  to: string;
}

export const DEFAULT_REPLACEMENTS: ReplacementRule[] = [
${replacementLines}
];
`,
  'utf8',
);

console.log(
  `✅ 已从桌面版导入：热词 ${hotwords.length} 条，替换词 ${replacements.length} 条`,
);
