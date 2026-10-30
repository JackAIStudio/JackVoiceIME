/**
 * Emoji 搜索的人工纠偏表。
 *
 * CLDR 的 `tts` 主名是给读屏用的，和「用户搜什么会想到它」经常不是一回事：
 *   - 哭 → CLDR 认为 😢 是「哭」，😭 是「放声大哭」，可绝大多数人搜「哭」想要的是 😭
 *   - 心 → CLDR 里 ❤️ 主名是「红心」，💟 才是「心型装饰」、💘 是「心中箭了」……
 *     但用户搜「心」想要的就是那颗红心
 *   - love → ❤️ 的中文主名是「红心」，英文主名是「red heart」，两个都不含 love
 *
 * 这张表就是在「词 → 应该排第一的 emoji」上加一层强制指定，优先级高于任何自动打分。
 * 只收那些实测下来明显反直觉的；能靠 CLDR 自然排对的不要写进来，免得越养越乱。
 *
 * 加一条之前先跑 `node lexicon/export-emoji-index.mjs`，用下面的自检确认它真的没排对。
 */
export const EMOJI_SEARCH_PINS: Array<{ term: string; emoji: string }> = [
  // 情绪
  { term: '哭', emoji: '😭' },
  { term: '大哭', emoji: '😭' },
  { term: 'ku', emoji: '😭' },
  { term: 'cry', emoji: '😭' },
  { term: 'crying', emoji: '😭' },
  { term: '笑', emoji: '🤣' },
  { term: '大笑', emoji: '🤣' },
  { term: 'xiao', emoji: '🤣' },
  { term: 'laugh', emoji: '🤣' },
  { term: 'lol', emoji: '🤣' },
  { term: '开心', emoji: '😄' },
  { term: 'kaixin', emoji: '😄' },
  { term: 'happy', emoji: '😄' },
  { term: '生气', emoji: '😠' },
  { term: 'shengqi', emoji: '😠' },
  { term: 'angry', emoji: '😠' },
  { term: '汗', emoji: '😅' },
  { term: 'han', emoji: '😅' },
  { term: '尴尬', emoji: '😅' },
  { term: '无语', emoji: '😑' },
  { term: 'wuyu', emoji: '😑' },

  // 心 / 爱
  { term: '心', emoji: '❤️' },
  { term: '爱心', emoji: '❤️' },
  { term: '红心', emoji: '❤️' },
  { term: 'xin', emoji: '❤️' },
  { term: 'heart', emoji: '❤️' },
  { term: 'love', emoji: '❤️' },
  { term: '爱', emoji: '❤️' },
  { term: 'ai', emoji: '❤️' },

  // 手势
  { term: '赞', emoji: '👍' },
  { term: '点赞', emoji: '👍' },
  { term: 'good', emoji: '👍' },
  { term: 'thumbsup', emoji: '👍' },
  { term: 'ok', emoji: '👌' },
  { term: '谢谢', emoji: '🙏' },
  { term: '感谢', emoji: '🙏' },
  { term: 'thanks', emoji: '🙏' },
  { term: '拜托', emoji: '🙏' },
  { term: '祈祷', emoji: '🙏' },
  { term: '鼓掌', emoji: '👏' },
  { term: 'clap', emoji: '👏' },
  { term: '抱抱', emoji: '🤗' },
  { term: 'hug', emoji: '🤗' },

  // 常用物件与场景
  { term: '红包', emoji: '🧧' },
  { term: 'hongbao', emoji: '🧧' },
  { term: '钱', emoji: '💰' },
  { term: 'qian', emoji: '💰' },
  { term: 'money', emoji: '💰' },
  { term: '生日', emoji: '🎂' },
  { term: 'shengri', emoji: '🎂' },
  { term: 'birthday', emoji: '🎂' },
  { term: '蛋糕', emoji: '🎂' },
  { term: 'cake', emoji: '🎂' },
  { term: '庆祝', emoji: '🎉' },
  { term: 'party', emoji: '🎉' },
  { term: '咖啡', emoji: '☕' },
  { term: 'kafei', emoji: '☕' },
  { term: 'coffee', emoji: '☕' },
  { term: '奶茶', emoji: '🧋' },
  { term: '啤酒', emoji: '🍺' },
  { term: 'beer', emoji: '🍺' },
  { term: '披萨', emoji: '🍕' },
  { term: 'pisa', emoji: '🍕' },
  { term: 'pizza', emoji: '🍕' },
  { term: '汉堡', emoji: '🍔' },
  { term: 'hamburger', emoji: '🍔' },
  { term: '火', emoji: '🔥' },
  { term: 'huo', emoji: '🔥' },
  { term: 'fire', emoji: '🔥' },
  { term: '玫瑰', emoji: '🌹' },
  { term: 'rose', emoji: '🌹' },
  { term: '花', emoji: '🌸' },
  { term: 'flower', emoji: '🌸' },

  // 动物
  { term: '猫', emoji: '🐈' },
  { term: 'mao', emoji: '🐈' },
  { term: 'cat', emoji: '🐈' },
  { term: '狗', emoji: '🐕' },
  { term: 'gou', emoji: '🐕' },
  { term: 'dog', emoji: '🐕' },
  { term: '猪', emoji: '🐖' },
  { term: 'pig', emoji: '🐖' },

  // 交通天文
  { term: '飞机', emoji: '✈️' },
  { term: 'feiji', emoji: '✈️' },
  { term: 'plane', emoji: '✈️' },
  { term: '火箭', emoji: '🚀' },
  { term: 'rocket', emoji: '🚀' },
  { term: '太阳', emoji: '☀️' },
  { term: 'sun', emoji: '☀️' },
  { term: '月亮', emoji: '🌙' },
  { term: 'moon', emoji: '🌙' },
  { term: '星星', emoji: '⭐' },
  { term: 'star', emoji: '⭐' },
  { term: '下雨', emoji: '🌧️' },
  { term: 'rain', emoji: '🌧️' },

  // 语气与反应
  { term: '厉害', emoji: '👍' },
  { term: '牛', emoji: '🐮' },
  { term: '加油', emoji: '💪' },
  { term: 'jiayou', emoji: '💪' },
  { term: '晚安', emoji: '😴' },
  { term: 'wanan', emoji: '😴' },
  { term: '睡觉', emoji: '😴' },
  { term: 'sleep', emoji: '😴' },
  { term: '饿', emoji: '😋' },
  { term: '好吃', emoji: '😋' },
  { term: 'yummy', emoji: '😋' },
  { term: '裂开', emoji: '💔' },
  { term: '心碎', emoji: '💔' },
  { term: 'broken', emoji: '💔' },
];
