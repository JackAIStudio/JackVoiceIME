/**
 * Emoji 索引单一数据源 → Android assets。
 *
 *   输入（首次运行自动下载到 lexicon/.cache/，该目录已 gitignore）
 *     emoji-test.txt        Unicode 官方全量清单 + 分类 + 顺序 + 英文名
 *     annotations/zh.json   CLDR 中文关键词（简体）
 *     annotations/en.json   CLDR 英文关键词
 *     pinyin.txt            mozillazg/pinyin-data 的 字→拼音（权威、含多音字）
 *     luna_pinyin.dict.yaml Rime 词表，用来给多字词取正确读音（音乐→yinyue）
 *     TSCharacters.txt      OpenCC 繁→简，把 Rime 的繁体词条折成简体
 *
 *   输出
 *     app/src/main/assets/emoji_index.json
 *
 * 手动跑：node lexicon/export-emoji-index.mjs
 *
 * 为什么要这么多源：
 *   - emoji-test 给的是「有什么 emoji、什么顺序、什么分类」，这是唯一权威来源。
 *   - 光有 emoji 没法搜。中文关键词只有 CLDR 有；英文关键词也只有 CLDR 有。
 *   - 中文关键词要能用拼音搜，就得反过来查每个字的读音。多音字（行长乐）只能靠
 *     Rime 词表按词取音，单靠字表会得到 yinxing/yinhang 这类错音。所以词表优先、
 *     字表兜底。Rime 词表是繁体，先折成简体再建索引，否则「音乐」查不到「音樂」。
 */

import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = join(HERE, '..');
const CACHE = join(HERE, '.cache');
const OUT = join(REPO_ROOT, 'app', 'src', 'main', 'assets', 'emoji_index.json');

const SOURCES = {
  emojiTest: {
    url: 'https://unicode.org/Public/emoji/16.0/emoji-test.txt',
    file: 'emoji-test.txt',
  },
  cldrZh: {
    url: 'https://cdn.jsdelivr.net/npm/cldr-annotations-full@latest/annotations/zh/annotations.json',
    file: 'cldr-zh.json',
  },
  cldrEn: {
    url: 'https://cdn.jsdelivr.net/npm/cldr-annotations-full@latest/annotations/en/annotations.json',
    file: 'cldr-en.json',
  },
  pinyin: {
    url: 'https://raw.githubusercontent.com/mozillazg/pinyin-data/master/pinyin.txt',
    file: 'pinyin.txt',
  },
  rimeDict: {
    file: 'luna_pinyin.dict.yaml',
    local: join(REPO_ROOT, 'app', 'data', 'rime', 'luna-pinyin', 'luna_pinyin.dict.yaml'),
  },
  t2s: {
    file: 'TSCharacters.txt',
    local: join(REPO_ROOT, 'app', 'src', 'main', 'assets', 'shared', 'opencc', 'TSCharacters.txt'),
  },
};

function ensureSources() {
  mkdirSync(CACHE, { recursive: true });
  for (const [name, src] of Object.entries(SOURCES)) {
    const dest = join(CACHE, src.file);
    if (src.local) {
      if (!existsSync(src.local)) {
        throw new Error(`缺少本地数据源 ${name}: ${src.local}`);
      }
      continue;
    }
    if (existsSync(dest)) continue;
    console.log(`↓ 下载 ${name} …`);
    try {
      execFileSync('curl', ['-fsSL', '--retry', '3', '-o', dest, src.url], { stdio: 'inherit' });
    } catch (e) {
      throw new Error(`下载 ${name} 失败（${src.url}）：${e.message}`);
    }
  }
}

const read = (file, local) => readFileSync(local ?? join(CACHE, file), 'utf8');

/** 去掉音调符号，ü 写成 v（和手机拼音键盘的输入习惯一致）。 */
function toneless(reading) {
  let out = '';
  for (const ch of reading.normalize('NFD')) {
    if (!/[\u0300-\u036f]/.test(ch)) out += ch;
  }
  return out.replace(/ü/g, 'v').replace(/Ü/g, 'v');
}

// ── OpenCC 繁→简 ────────────────────────────────────────────────────────────
function loadT2S() {
  const map = new Map();
  for (const line of read(SOURCES.t2s.file, SOURCES.t2s.local).split('\n')) {
    if (line.startsWith('#')) continue;
    const [tra, vals] = line.split('\t');
    if (!tra || !vals) continue;
    map.set(tra, vals.split(' ')[0]);
  }
  return map;
}

// ── 字→拼音（pinyin-data）────────────────────────────────────────────────────
function loadCharPinyin() {
  const map = new Map();
  for (const line of read(SOURCES.pinyin.file, SOURCES.pinyin.local).split('\n')) {
    if (!line.startsWith('U+')) continue;
    const m = line.match(/^U\+([0-9A-F]+):\s*(.*?)\s*#/);
    if (!m) continue;
    const ch = String.fromCodePoint(parseInt(m[1], 16));
    const readings = [];
    for (const raw of m[2].split(',')) {
      const r = toneless(raw.trim());
      if (r && !readings.includes(r)) readings.push(r);
    }
    if (readings.length) map.set(ch, readings);
  }
  return map;
}

// ── 词→拼音（Rime 词表，折成简体）───────────────────────────────────────────
function loadWordPinyin(t2s) {
  const byWord = new Map();
  const lines = read(SOURCES.rimeDict.file, SOURCES.rimeDict.local).split('\n');
  let inBody = false;
  for (const line of lines) {
    if (line.startsWith('...')) {
      inBody = true;
      continue;
    }
    if (!inBody || !line || line.startsWith('#')) continue;
    const [word, pinyin, weightRaw] = line.split('\t');
    if (!word || !pinyin) continue;
    let simplified = '';
    for (const ch of word) simplified += t2s.get(ch) ?? ch;
    // 繁简折字后字数变了说明这条词条对不上，跳过（避免错位）
    if ([...simplified].length !== [...word].length) continue;
    const weight = weightRaw?.endsWith('%') ? parseFloat(weightRaw) / 100 : 1;
    const prev = byWord.get(simplified);
    if (!prev || prev.weight < weight) {
      byWord.set(simplified, { weight, pinyin: pinyin.replace(/\s+/g, '') });
    }
  }
  return byWord;
}

/** 给一段中文取无声调拼音。词表优先（多音字靠它），逐字兜底。 */
function makePinyinResolver(wordPinyin, charPinyin) {
  /** @returns {{pinyin: string, initials: string, ambiguous: boolean} | null} */
  return function resolve(text) {
    const chars = [...text];
    let syllables;
    let ambiguous = false;

    if (wordPinyin.has(text) && chars.length > 1) {
      // 词表给的是连写拼音，按词内音节切分要靠字表反推，这里退回逐字但标不歧义
      const wordReading = wordPinyin.get(text).pinyin;
      syllables = splitSyllables(wordReading, chars, charPinyin);
      if (!syllables) return null;
    } else {
      syllables = [];
      for (const ch of chars) {
        const readings = charPinyin.get(ch);
        if (!readings) return null;
        if (readings.length > 1) ambiguous = true;
        syllables.push(readings[0]);
      }
    }

    if (!syllables.length) return null;
    return {
      pinyin: syllables.join(''),
      initials: syllables.map((s) => s[0]).join(''),
      ambiguous,
    };
  };
}

/**
 * 把连写的词拼音按字数切开。用每个字的候选读音做递归匹配，取第一个能切通的解。
 * `yinyue` + [音,乐] → [`yin`,`yue`]（乐在这里必须读 yue，不能是 le）。
 */
function splitSyllables(wordReading, chars, charPinyin) {
  const memo = new Map();
  const walk = (pos, ci) => {
    if (ci === chars.length) return pos === wordReading.length ? [] : null;
    const key = `${pos}:${ci}`;
    if (memo.has(key)) return memo.get(key);
    const readings = charPinyin.get(chars[ci]) ?? [];
    for (const r of readings) {
      if (wordReading.startsWith(r, pos)) {
        const rest = walk(pos + r.length, ci + 1);
        if (rest) {
          const result = [r, ...rest];
          memo.set(key, result);
          return result;
        }
      }
    }
    memo.set(key, null);
    return null;
  };
  return walk(0, 0);
}

// ── emoji-test：清单 + 分类 + 顺序 + 英文名 ─────────────────────────────────
const GROUP_ZH = {
  'Smileys & Emotion': '表情',
  'People & Body': '人物',
  'Animals & Nature': '动物与自然',
  'Food & Drink': '食物与饮料',
  'Travel & Places': '旅行与地点',
  Activities: '活动',
  Objects: '物品',
  Symbols: '符号',
  Flags: '旗帜',
};

/** 分类横条上那一格图标。 */
const GROUP_ICON = {
  'Smileys & Emotion': '😀',
  'People & Body': '👋',
  'Animals & Nature': '🐶',
  'Food & Drink': '🍔',
  'Travel & Places': '✈️',
  Activities: '🎮',
  Objects: '💡',
  Symbols: '❤️',
  Flags: '🏳️',
};

const GROUP_ORDER = [
  'Smileys & Emotion',
  'People & Body',
  'Animals & Nature',
  'Food & Drink',
  'Travel & Places',
  'Activities',
  'Objects',
  'Symbols',
  'Flags',
];

/**
 * 解析 emoji-test。
 *
 * 只收 fully-qualified，且去掉带肤色修饰符的组合——肤色变体不单独占一格，
 * 它们挂在基础 emoji 的 `variants` 上，长按才出来。否则 👋🏻👋🏼👋🏽 会把列表撑爆
 * （People & Body 一个分类就 2261 条，其中绝大多数是肤色组合）。
 */
function loadEmoji(emojiTestText) {
  const items = [];
  let group = null;
  for (const line of emojiTestText.split('\n')) {
    if (line.startsWith('# group:')) {
      group = line.slice(8).trim();
      continue;
    }
    if (!group || group === 'Component') continue;
    // 行尾是「# <emoji> <E版本号> <名称>」，版本号不是名称的一部分，必须剥掉，
    // 否则会解析出 e0 / e1 / e12 这类假词条。
    const m = line.match(/^([0-9A-F ]+);\s*fully-qualified\s*#\s*(\S+)\s+E[0-9.]+\s+(.*)$/);
    if (!m) continue;
    const emoji = m[1]
      .trim()
      .split(/\s+/)
      .map((cp) => String.fromCodePoint(parseInt(cp, 16)))
      .join('');
    const name = m[3].trim();
    items.push({ emoji, name, group });
  }
  return items;
}

const SKIN = new Set(['\u{1F3FB}', '\u{1F3FC}', '\u{1F3FD}', '\u{1F3FE}', '\u{1F3FF}']);
const hasSkin = (s) => [...s].some((ch) => SKIN.has(ch));
const stripSkin = (s) => [...s].filter((ch) => !SKIN.has(ch)).join('');

/** 英文名的词也要进索引：CLDR 关键词并不总包含官方名（`grinning face`）。 */
const NAME_STOP = new Set(['face', 'with', 'and', 'the', 'of', 'a', 'an', 'in']);

function nameWords(name) {
  return name
    .toLowerCase()
    .replace(/[’']/g, '')
    .split(/[^a-z0-9]+/)
    .filter((w) => w.length >= 2 && !NAME_STOP.has(w));
}

// ── 构建 ────────────────────────────────────────────────────────────────────
ensureSources();

const t2s = loadT2S();
const charPinyin = loadCharPinyin();
const wordPinyin = loadWordPinyin(t2s);
const resolvePinyin = makePinyinResolver(wordPinyin, charPinyin);

const cldrZh = JSON.parse(read(SOURCES.cldrZh.file, SOURCES.cldrZh.local)).annotations.annotations;
const cldrEn = JSON.parse(read(SOURCES.cldrEn.file, SOURCES.cldrEn.local));

/**
 * 常用英文词表。
 *
 * CLDR 的英文关键词里长尾极多（`aesculapius`、`absolutely`、`accessibility`），
 * 全收进来索引会到 400KB+，而这些词没人会去搜。英文这一侧只留「词表里有的常用词」
 * 和「4 字母以内的短词」（ok / ice / cat / 1234 这类短词本身就有搜索价值）。
 * 中文侧不动——中文关键词本来就不长，长尾是「一片比萨」这种整句，留着无害。
 */
const commonEnglish = new Set(
  readFileSync(join(REPO_ROOT, 'app', 'src', 'main', 'assets', 'en_words.txt'), 'utf8')
    .split('\n')
    .map((w) => w.trim().toLowerCase())
    .filter(Boolean),
);

const isEnglishTerm = (t) => /^[a-z0-9 ]+$/.test(t);
const keepEnglishTerm = (t) => commonEnglish.has(t) || t.length <= 4;

/**
 * 读人工纠偏表 `lexicon/src/emoji-pins.ts`。
 *
 * 这个脚本是 .mjs，不能直接 import .ts，和 export-assets.mjs 一样用正则抠出来——
 * 数据源仍然是那个 .ts 文件，只有一个源头。
 */
function loadPins() {
  const src = readFileSync(join(HERE, 'src', 'emoji-pins.ts'), 'utf8');
  const pins = [];
  const re = /\{\s*term:\s*'((?:[^'\\]|\\.)*)'\s*,\s*emoji:\s*'((?:[^'\\]|\\.)*)'\s*\}/g;
  for (const m of src.matchAll(re)) pins.push({ term: m[1], emoji: m[2] });
  if (!pins.length) throw new Error('emoji-pins.ts 里一条纠偏都没解析出来，格式可能变了');
  return pins;
}

/** CLDR 的 key 带 VS16，emoji-test 的串不一定带；统一去掉再查。 */
const dropVS = (s) => s.replace(/[\uFE0E\uFE0F]/g, '');

// 存整个 {default, tts} 对象，不能只存 default 数组——tts 是主名，排序要用。
const zhIndex = new Map();
for (const [k, v] of Object.entries(cldrZh)) zhIndex.set(dropVS(k), v);
const enIndex = new Map();
for (const [k, v] of Object.entries(cldrEn)) enIndex.set(dropVS(k), v);

const all = loadEmoji(read(SOURCES.emojiTest.file, SOURCES.emojiTest.local));

// 基础 emoji（无肤色）＋ 肤色变体挂到基础项上
const base = [];
const baseByKey = new Map();
const variantsByKey = new Map();
for (const it of all) {
  if (hasSkin(it.emoji)) {
    const key = stripSkin(it.emoji);
    if (!variantsByKey.has(key)) variantsByKey.set(key, []);
    variantsByKey.get(key).push(it.emoji);
    continue;
  }
  const key = dropVS(it.emoji);
  if (baseByKey.has(key)) continue;
  baseByKey.set(key, base.length);
  base.push(it);
}

console.log(`基础 emoji ${base.length} 个（原始 ${all.length}，肤色变体已折叠）`);

// 分类区间
const categories = [];
for (const g of GROUP_ORDER) {
  const idxs = base.map((e, i) => (e.group === g ? i : -1)).filter((i) => i >= 0);
  if (!idxs.length) continue;
  categories.push({
    i: GROUP_ICON[g],
    z: GROUP_ZH[g] ?? g,
    s: idxs[0],
    c: idxs.length,
  });
}

// 倒排索引
/**
 * emoji 的「主名」：CLDR 的 tts 字段。
 *
 * 搜索排序要区分「这就是我要的那个」和「它的关键词里碰巧有这个字」。
 * 🍄‍🟫 和 🍕 的英文关键词里都有 pizza，但只有 🍕 的主名是 pizza；
 * 🦴 的中文关键词里有「狗」，可它主名是「骨头」。
 *
 * 注意不能拿 default 列表的下标当相关度——CLDR 那个列表是按字母序排的，
 * 不是按相关性；`tts` 才是那个「一句话说这是什么」的字段。
 */
const primaryNames = new Map();
function addPrimary(idx, text) {
  if (!text) return;
  const cur = primaryNames.get(idx) ?? [];
  if (!cur.includes(text)) cur.push(text);
  primaryNames.set(idx, cur);
}

/**
 * 主名的拼音。排序用。
 *
 * 光有中文主名不够：用户打 `gou` 时，🦴（主名「骨头」）和 🐕（主名「狗」）的关键词里
 * 都有「狗」，光看关键词分不出来。按主名的拼音看，`gou` 正好等于 🐕 的主名拼音，
 * 只是 🦴 主名拼音的前缀——这就分得开了。
 */
const primaryPinyin = new Map();
function addPrimaryPinyin(idx, pinyin, initials) {
  const cur = primaryPinyin.get(idx) ?? [];
  for (const t of [pinyin, initials]) {
    if (t && !cur.includes(t)) cur.push(t);
  }
  primaryPinyin.set(idx, cur);
}

const MAX_POSTING = 80; // 单个词的命中上限，防「一」「人」这类词把索引撑爆
const postings = new Map();

function addTerm(term, idx) {
  if (!term) return;
  const list = postings.get(term);
  if (!list) {
    postings.set(term, [idx]);
    return;
  }
  if (list[list.length - 1] === idx) return; // 同一个 emoji 的重复词
  if (list.length >= MAX_POSTING) return;
  list.push(idx);
}

let zhTermCount = 0;
let pinyinTermCount = 0;
let enTermCount = 0;
let ambiguousWords = [];

for (let i = 0; i < base.length; i++) {
  const { emoji, name } = base[i];
  const key = dropVS(emoji);

  // 主名（tts）——中英各一份，排序时优先。
  for (const t of zhIndex.get(key)?.tts ?? []) {
    addPrimary(i, t);
    const py = resolvePinyin(t);
    if (py) addPrimaryPinyin(i, py.pinyin, py.initials);
  }
  for (const t of enIndex.get(key)?.tts ?? []) addPrimary(i, t.toLowerCase());

  for (const raw of zhIndex.get(key)?.default ?? []) {
    const term = raw.replace(/\s+/g, '');
    if (!term || !/^[\u3400-\u9fff]+$/.test(term)) continue;
    addTerm(term, i);
    zhTermCount++;
    const py = resolvePinyin(term);
    if (py) {
      addTerm(py.pinyin, i);
      if (py.initials.length >= 2) addTerm(py.initials, i);
      pinyinTermCount++;
      if (py.ambiguous) ambiguousWords.push(term);
    }
  }

  const enTerms = new Set();
  for (const raw of enIndex.get(key)?.default ?? []) {
    const t = raw.toLowerCase().trim();
    if (t.length >= 2) enTerms.add(t);
  }
  for (const w of nameWords(name)) enTerms.add(w);
  for (const t of enTerms) {
    addTerm(t, i);
    enTermCount++;
  }
}

// 词 → emoji 的下标表。英文长尾在这里统一过滤一次，中文不动。
const index = {};
let droppedEnglish = 0;
for (const term of [...postings.keys()].sort()) {
  if (isEnglishTerm(term) && !keepEnglishTerm(term)) {
    droppedEnglish++;
    continue;
  }
  index[term] = postings.get(term);
}

/**
 * 人工纠偏：把指定 emoji 提到某个词的命中列表第一位。
 *
 * 运行时排序是「档位 > 词条相关度 > 下标」，这里直接把目标 emoji 插到 posting 最前面，
 * 再标记成「钉住」——运行时看到 pinned 就跳过其余打分，直接排第一。
 * 这样纠偏表改一行就生效，不用去调那套打分公式。
 */
const pins = loadPins();
const emojiIndexByChar = new Map(base.map((e, i) => [e.emoji, i]));
const pinned = {};
let appliedPins = 0;
let skippedPins = 0;
for (const { term, emoji } of pins) {
  const key = term.toLowerCase();
  const i = emojiIndexByChar.get(emoji);
  if (i === undefined) {
    // 纠偏表里写了个当前 Unicode 版本没有的 emoji，说明表过期了，直接报出来。
    console.warn(`⚠️  纠偏表里的 ${emoji}（${term}）不在 emoji 16.0 里，已忽略`);
    skippedPins++;
    continue;
  }
  const list = index[key] ?? [];
  const rest = list.filter((x) => x !== i);
  index[key] = [i, ...rest];
  pinned[key] = i;
  appliedPins++;
}

// 主名表：下标 → [中文主名, 英文主名]。搜索时命中主名的排前。
const primary = {};
for (const [i, names] of primaryNames) {
  if (names.length) primary[i] = names;
}

// 主名拼音表：下标 → [全拼, 首字母]。打 gou 时 🐕（狗）要压过 🦴（骨头）。
const primaryPy = {};
for (const [i, list] of primaryPinyin) {
  if (list.length) primaryPy[i] = list;
}

// 肤色变体表：只存「基础码点 + 修饰符」拼不出来的那些（ZWJ 组合）
const variantMap = {};
for (let i = 0; i < base.length; i++) {
  const key = dropVS(base[i].emoji);
  const vs = variantsByKey.get(key);
  if (!vs || vs.length === 0) continue;
  variantMap[i] = vs;
}

const payload = {
  v: '16.0',
  // 必须是数组，不能连写成字符串：emoji 是多码点的（ZWJ 组合、变体选择符），
  // 按码点切会切碎，🍕 会变成 \u{1F355}\uFE0F 两段。
  emojis: base.map((e) => e.emoji),
  categories,
  index,
  primary,
  primaryPy,
  pinned,
  variants: variantMap,
};

/**
 * 写**明文 JSON**，不要自己 gzip。
 *
 * 试过写 `.json.gz`，但 AAPT 打包 assets 时会先把 `.gz` 解压、再用 deflate 压进 APK，
 * 结果 APK 里仍然是明文（151KB），还多出一次解压和一层坑。既然打包那一步本来就会压，
 * 这里直接给明文，运行时用最普通的 assets 读取即可，少一个 GZIPInputStream。
 */
writeFileSync(OUT, JSON.stringify(payload));

const packed = readFileSync(OUT);
console.log(`✅ ${OUT}`);
console.log(`   emoji ${base.length} 个，分类 ${categories.length} 个`);
console.log(`   词条：中文 ${zhTermCount} / 拼音 ${pinyinTermCount} / 英文 ${enTermCount}`);
console.log(`   去重后 ${Object.keys(index).length} 个索引键（英文长尾丢 ${droppedEnglish} 个）`);
console.log(`   多音字词 ${ambiguousWords.length} 个（取首读音，必要时人工校对）`);
console.log(`   人工纠偏 ${appliedPins} 条生效${skippedPins ? `，${skippedPins} 条失效` : ''}`);
console.log(`   体积 ${(packed.length / 1024).toFixed(0)} KB（APK 打包时还会再 deflate 到约 150 KB）`);
