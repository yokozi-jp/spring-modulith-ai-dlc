#!/usr/bin/env node
// skills-check: skills-lock.json と .agents/skills、.kiro/skills の整合を検査する。
// .kiro/.kiro-plugins-installed に載るファイル（cursor-plugins の kiro/install.sh が入れたもの）は、記録した内容ハッシュと比べる。
// 実行: node tooling/skills-check/skills-check.mjs [ルート。省略するとカレント]（task skills-check が呼ぶ）
// 手順の正文は docs/agents/skill-sources.md にある。
import { createHash } from "node:crypto";
import { existsSync, lstatSync, readdirSync, readFileSync, readlinkSync, statSync } from "node:fs";
import { join, relative, sep } from "node:path";

const root = process.argv[2] ?? ".";
const errors = [];

// .git と node_modules は、skills CLI の computeSkillFolderHash と同じく内容ハッシュから除く。
// kiro/install.sh は git が追跡するファイルだけを入れ、cursor-plugins は .git と node_modules の下を追跡しないため、記録した内容ハッシュもこの2つを含まない。
// この前提が崩れると内容ハッシュが一致せずに失敗するため、黙って見逃すことはない。node_modules はこのリポジトリでも .gitignore で無視する。
const skipped = new Set([".git", "node_modules"]);

// dir の下のファイルを相対パスで返す。シンボリックリンクは files に入れず symlinks に入れる。
function listFiles(dir) {
  const files = [];
  const symlinks = [];
  const walk = (current) => {
    for (const entry of readdirSync(current, { withFileTypes: true })) {
      if (skipped.has(entry.name)) continue;
      const path = join(current, entry.name);
      const rel = relative(dir, path).split(sep).join("/");
      if (entry.isDirectory()) walk(path);
      else if (entry.isFile()) files.push(rel);
      else if (entry.isSymbolicLink()) symlinks.push(rel);
    }
  };
  walk(dir);
  return { files, symlinks };
}

function hashFiles(dir, files) {
  const hash = createHash("sha256");
  for (const file of files) {
    hash.update(file);
    hash.update(readFileSync(join(dir, file)));
  }
  return hash.digest("hex");
}

// skills CLI の computeSkillFolderHash と同じ。相対パスを localeCompare で並べる。
function folderHash(dir) {
  return hashFiles(dir, listFiles(dir).files.sort((a, b) => a.localeCompare(b)));
}

// kiro/install.sh の content_hash と同じ。相対パスをバイト順に並べる。ファイルは空のパスで自身の内容だけを使う。
function pluginHash(path) {
  if (!statSync(path).isDirectory()) return createHash("sha256").update(readFileSync(path)).digest("hex");
  return hashFiles(path, listFiles(path).files.sort((a, b) => Buffer.compare(Buffer.from(a), Buffer.from(b))));
}

// SKILL.md のフロントマターを読み、1行で書いた値を fields に入れる。
// 各行は「キー:」、字下げ、「- 」、「#」のどれかで始まる。説明文を一文ごとに改行して続きの行を字下げしないと、Kiro はそのスキルを読み込まない。
function parseFrontmatter(text) {
  const lines = text.replaceAll("\r\n", "\n").split("\n");
  if (lines[0] !== "---") return { fields: {}, error: "先頭がフロントマターの --- ではありません" };
  const end = lines.indexOf("---", 1);
  if (end < 0) return { fields: {}, error: "フロントマターが --- で閉じていません" };
  const fields = {};
  for (const [index, line] of lines.slice(1, end).entries()) {
    if (line === "" || /^(\s|-(\s|$)|#)/.test(line)) continue;
    const key = line.match(/^([\w.-]+):(\s|$)/);
    if (!key) return { fields: {}, error: `フロントマターの ${index + 2} 行目が「キー:」、字下げ、「- 」、「#」のどれでも始まりません（説明文の続きの行は字下げします）` };
    fields[key[1]] = line.slice(key[0].length).trim().replace(/^(['"])(.*)\1$/, "$2");
  }
  return { fields };
}

function names(dir, filter = () => true) {
  try {
    return readdirSync(join(root, dir), { withFileTypes: true }).filter(filter).map((entry) => entry.name);
  } catch {
    return [];
  }
}

const directories = (dir) => names(dir, (entry) => entry.isDirectory() || entry.isSymbolicLink());

// skills-lock.json の項目。取得元のないスキルかどうかは sourceType だけで決める（skills CLI は書き戻すときに source の "local" を "./local" に変える）。
const lock = Object.entries(JSON.parse(readFileSync(join(root, "skills-lock.json"), "utf8")).skills).map(([name, entry]) => ({
  name,
  entry,
  local: entry.sourceType === "local",
}));

// .kiro/.kiro-plugins-installed は1行目が "# cursor-plugins <コミット>"、2行目以降が "<.kiro からのパス>\t<内容ハッシュ>" の行でできている。
// 1行目が # で始まらなければ、取得元コミットの行がないものとして全行を項目として読む。
const manifestFile = join(root, ".kiro/.kiro-plugins-installed");
const manifest = (() => {
  if (!existsSync(manifestFile)) return undefined;
  const lines = readFileSync(manifestFile, "utf8").split("\n");
  if (lines.at(-1) === "") lines.pop();
  const first = lines[0]?.startsWith("#") ? 1 : 0;
  return {
    commitLine: first ? lines[0] : undefined,
    rows: lines.map((text, index) => ({ lineNumber: index + 1, text })).slice(first).filter(({ text }) => text !== ""),
  };
})();

// kiro/install.sh は更新時に記録のパスを削除するため、.kiro 直下の skills、agents、steering の1要素（. と .. を除く）と kiro-permissions.yaml だけを認める。
const manifestPath = /^(?:(?:skills|agents|steering)\/(?!\.\.?$)[^/]+|kiro-permissions\.yaml)$/;
const commitLine = /^# cursor-plugins ([0-9a-f]{40})(-dirty)?$/;

// 記録の各行と、記録に載るべきファイルを検査し、記録にあるスキルの名前を返す。
function checkPluginManifest() {
  const paths = new Set();
  const commit = manifest?.commitLine?.match(commitLine);
  if (manifest && !commit) {
    errors.push(".kiro/.kiro-plugins-installed の1行目は「# cursor-plugins <40 桁のコミット SHA>」でなければなりません（kiro/install.sh で入れ直してください）");
  } else if (commit?.[2]) {
    errors.push(`.kiro/.kiro-plugins-installed の取得元コミット ${commit[1]}-dirty は未コミットの変更を含みます。cursor-plugins でコミットしてから入れ直してください`);
  }
  for (const { lineNumber, text } of manifest?.rows ?? []) {
    const where = `.kiro/.kiro-plugins-installed の ${lineNumber} 行目`;
    if (text.startsWith("#")) {
      errors.push(`${where}: # で始まる行は1行目の取得元コミットだけに置けます`);
      continue;
    }
    const [path, recorded] = text.split("\t");
    if (!manifestPath.test(path)) {
      errors.push(`${where}: ${path} は skills、agents、steering の直下の要素でも kiro-permissions.yaml でもありません。kiro/install.sh の更新で消されるため認められません`);
      continue;
    }
    if (paths.has(path)) {
      errors.push(`${where}: ${path} が重複しています`);
      continue;
    }
    paths.add(path);
    const file = join(root, ".kiro", path);
    if (!recorded) {
      errors.push(`.kiro/${path}: .kiro/.kiro-plugins-installed に内容ハッシュがありません（kiro/install.sh で入れ直してください）`);
    } else if (!existsSync(file)) {
      errors.push(`.kiro/${path}: .kiro/.kiro-plugins-installed にありますが、ファイルがありません`);
    } else {
      if (statSync(file).isDirectory()) {
        for (const link of listFiles(file).symlinks) errors.push(`.kiro/${path}/${link}: シンボリックリンクは認められません（内容ハッシュに含まれず、改ざんを検出できません）`);
      }
      if (pluginHash(file) !== recorded) {
        errors.push(`.kiro/${path}: 内容ハッシュが .kiro/.kiro-plugins-installed と一致しません（入れたファイルを編集していないか確認してください）`);
      }
    }
  }

  // kiro/install.sh が入れる場所にあるファイルは、記録に載っていなければならない。記録から消した行の先は、編集しても検出できないため。
  // .kiro/agents にはこのリポジトリのファイルを置かない。.kiro/steering はこのリポジトリの steering と同居するため、
  // kiro/install.sh が入れる steering と同じく、記録にあるスキルと同じ名前のもの（kiro-adapter.md）だけを求める。
  // .kiro/skills の記録にないディレクトリは checkCrossRecords が検出する。
  const skills = new Set([...paths].filter((path) => path.startsWith("skills/")).map((path) => path.slice("skills/".length)));
  const expected = [
    ...names(".kiro/agents").map((name) => `agents/${name}`),
    ...names(".kiro/steering").filter((name) => skills.has(name.replace(/\.md$/, ""))).map((name) => `steering/${name}`),
    ...(existsSync(join(root, ".kiro/kiro-permissions.yaml")) ? ["kiro-permissions.yaml"] : []),
  ];
  for (const path of expected) {
    if (!paths.has(path)) errors.push(`.kiro/${path} が .kiro/.kiro-plugins-installed にありません。記録にないファイルは編集を検出できません`);
  }
  return skills;
}

function checkLock() {
  const refsBySource = new Map();
  for (const { name, entry, local } of lock) {
    const dir = join(root, ".agents/skills", name);
    let skillText;
    try {
      skillText = readFileSync(join(dir, "SKILL.md"), "utf8");
    } catch {
      errors.push(`${name}: .agents/skills/${name}/SKILL.md がありません`);
      continue;
    }
    const { fields, error } = parseFrontmatter(skillText);
    if (error) {
      errors.push(`${name}: SKILL.md の${error}`);
    } else {
      if (fields.name !== name) errors.push(`${name}: SKILL.md の name がディレクトリ名と一致しません`);
      if (!fields.description) errors.push(`${name}: SKILL.md のフロントマターに description がありません`);
    }
    if (local && !entry.note) errors.push(`${name}: 取得元のないスキル（sourceType が local）には note が必要です`);

    const link = join(root, ".kiro/skills", name);
    let isLink = false;
    try {
      isLink = lstatSync(link).isSymbolicLink();
    } catch {
      // 無ければ下のエラーにする
    }
    if (!isLink || readlinkSync(link) !== `../../.agents/skills/${name}`) {
      errors.push(`${name}: .kiro/skills/${name} が ../../.agents/skills/${name} へのシンボリックリンクではありません`);
    }

    const actual = folderHash(dir);
    if (actual !== entry.computedHash) {
      errors.push(`${name}: 内容ハッシュ ${actual} が skills-lock.json の computedHash と一致しません（上流のファイルを編集していないか確認してください。note に理由を書いて意図して変えたときは、computedHash をこの値にします）`);
    }
    if (!local) refsBySource.set(entry.source, new Set([...(refsBySource.get(entry.source) ?? []), entry.ref]));
  }
  for (const [source, refs] of refsBySource) {
    if (refs.size > 1) errors.push(`${source}: ref が複数あります（${[...refs].join(", ")}）。取得元ごとに固定コミットを1つにしてください`);
  }
}

function checkCrossRecords(pluginSkills) {
  const lockNames = new Set(lock.map(({ name }) => name));
  for (const name of lockNames) {
    if (pluginSkills.has(name)) errors.push(`${name}: skills-lock.json と .kiro/.kiro-plugins-installed の両方にあります。どちらか一方で管理してください`);
  }
  for (const name of directories(".agents/skills")) {
    if (!lockNames.has(name)) errors.push(`.agents/skills/${name} が skills-lock.json にありません`);
  }
  for (const name of directories(".kiro/skills")) {
    if (!lockNames.has(name) && !pluginSkills.has(name)) errors.push(`.kiro/skills/${name} が skills-lock.json にも .kiro/.kiro-plugins-installed にもありません`);
  }
}

const pluginSkills = checkPluginManifest();
checkLock();
checkCrossRecords(pluginSkills);

if (errors.length > 0) {
  console.error(`✗ スキルの整合に ${errors.length} 件の問題があります。\n${errors.map((error) => `    - ${error}`).join("\n")}`);
  process.exit(1);
}
console.log(`skills-check OK: ${lock.length} スキル、kiro/install.sh で入れた ${pluginSkills.size} スキル`);
