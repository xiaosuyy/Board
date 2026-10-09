// board_common.dict.yaml 生成脚本（开发期一次性运行，不参与 App 构建）
//
// 作用：从 assets/rime/essay.txt 取高频 2–4 字词，用 pinyin-pro 自动注音，
//       并追加一份人工整理的「常用语气词 / 叠词（含口语编码）」，
//       生成 android/app/src/main/assets/rime/board_common.dict.yaml。
//
// 依赖：Node.js + npm 包 pinyin-pro（仅开发期工具依赖，不是 App 运行时依赖）
//   npm install pinyin-pro
//   PINYIN_PRO_DIR=<含 node_modules 的目录> node tools/gen_board_common.mjs
//
// 可选环境变量：MIN_FREQ（默认 200）、MAX_ENTRIES（默认 40000）
//
// 生成后：把 RimeDataInstaller.ASSET_VERSION 加一，App 下次启动会自动重发并部署。

import fs from "node:fs";
import path from "node:path";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
const { pinyin } = require(
  process.env.PINYIN_PRO_DIR + "/node_modules/pinyin-pro",
);

const RIME = "E:/OpenCode/blueboard/android/app/src/main/assets/rime";
const ESSAY = path.join(RIME, "essay.txt");
const OUT = path.join(RIME, "board_common.dict.yaml");

const MIN_FREQ = Number(process.env.MIN_FREQ ?? 200);
const MAX_ENTRIES = Number(process.env.MAX_ENTRIES ?? 40000);

const isCjk = (ch) => {
  const c = ch.codePointAt(0);
  return c >= 0x4e00 && c <= 0x9fff;
};

const rows = [];
for (const line of fs.readFileSync(ESSAY, "utf8").split(/\r?\n/)) {
  if (!line) continue;
  const i = line.indexOf("\t");
  if (i < 0) continue;
  const word = line.slice(0, i);
  const freq = parseInt(line.slice(i + 1).trim(), 10);
  if (!Number.isFinite(freq) || freq < MIN_FREQ) continue;
  const len = [...word].length;
  if (len < 2 || len > 4) continue;
  if (![...word].every(isCjk)) continue;
  rows.push({ word, freq });
}

rows.sort((a, b) => b.freq - a.freq);
const selected = rows.slice(0, MAX_ENTRIES);

const seen = new Set();
const out = [];
let skipped = 0;
for (const { word, freq } of selected) {
  if (seen.has(word)) continue;
  seen.add(word);
  const arr = pinyin(word, { toneType: "none", type: "array", v: true });
  if (!Array.isArray(arr) || arr.length !== [...word].length) {
    skipped++;
    continue;
  }
  const code = arr.join(" ").toLowerCase();
  if (!/^[a-z ]+$/.test(code)) {
    skipped++;
    continue;
  }
  out.push(`${word}\t${code}\t${freq}`);
}

// ---------------------------------------------------------------------------
// 人工补充：常用语气词 / 叠词（对齐微信：把用户实际会敲的「口语编码」也收进来）。
// 这些词的拼音在标准拼音里不是这么打（嗯=en、哦=o…），但口语里常直接敲 e/o，
// 微信对 `ee` 就能给出「嗯嗯」。权重取 essay 词频（没有的给一个较小值）。
// ---------------------------------------------------------------------------
const CURATED = [
  ["嗯", "e", 96536],
  ["嗯嗯", "e e", 2637],
  ["哦", "e", 21790],
  ["哦哦", "e e", 1976],
  ["呃", "e", 7963],
  ["呃呃", "e e", 900],
  ["额", "e", 4000],
  ["额额", "e e", 1200],
  ["唉", "ai", 20000],
  ["哎", "ai", 15000],
  ["呀", "ya", 40000],
  ["哟", "yo", 8000],
  ["噢", "o", 5000],
  ["嘿", "hei", 6000],
  ["哈", "ha", 50000],
  ["哈哈", "ha ha", 122324],
  ["呵呵", "he he", 250099],
  ["嘿嘿", "hei hei", 30819],
  ["咦", "yi", 4000],
  ["哇", "wa", 20000],
  ["耶", "ye", 8000],
  ["嗨", "hai", 12000],
];
for (const [w, code, weight] of CURATED) {
  if (seen.has(w + "\t" + code)) continue;
  seen.add(w + "\t" + code);
  out.push(`${w}\t${code}\t${weight}`);
}

const header =
  "---\n" +
  "name: board_common\n" +
  'version: "1"\n' +
  "sort: by_weight\n" +
  "use_preset_vocabulary: false\n" +
  "columns:\n  - text\n  - code\n  - weight\n" +
  "...\n";

fs.writeFileSync(OUT, header + out.join("\n") + "\n");
console.log("essay 命中", rows.length, "写入", out.length, "跳过(注音失败)", skipped);
console.log("文件", OUT, (fs.statSync(OUT).size / 1024 / 1024).toFixed(2), "MB");
