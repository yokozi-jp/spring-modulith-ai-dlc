---
type: Runbook
title: スキルの取得元と更新
description: 外部スキルの取得元と記録、固定コミットを変えてレビューする導入と更新の手順、このリポジトリで書くスキルの記録、入れたファイルの編集の禁止、Kiro の権限の雛形の置き方、検証を定める Runbook。スキルを導入、更新するとき、検証スキルなどをこのリポジトリで書いたとき、clone して Kiro を使い始めるとき、task skills-check が失敗したときに読む。
tags: [runbook, agent-skills, pstack, thermos, matt-pocock]
---

# スキルの取得元と更新

## 要約

- 外部スキルは `skills-lock.json` で管理するものと、`.kiro/.kiro-plugins-installed` で管理するものに分かれ、どちらも取得元のコミットに固定する（[ADR-039](../adr/ADR-039-adopt-matt-pocock-skills-workflow.md)、[ADR-084](../adr/ADR-084-adopt-rgdd-with-pstack-and-thermos.md)）。
- 更新は固定コミットを変える Pull Request で行い、旧と新のコミットの差分をレビューしてから入れる。
- 入れたスキルは編集しない。
- このリポジトリで書くスキルも `skills-lock.json` に記録する。
- 導入、更新、記録のあとは `task skills-check` を実行する。

## 取得元と記録

スキルの取得元ごとに、記録するファイルと固定コミットの置き場所は次のとおり。

| 取得元                     | スキル                                                                                    | 記録                            | 固定コミット                          |
| -------------------------- | ----------------------------------------------------------------------------------------- | ------------------------------- | ------------------------------------- |
| `mattpocock/skills`        | Matt Pocock のスキル                                                                      | `skills-lock.json`              | 項目の `ref`                          |
| `anthropics/skills`        | `skill-creator`                                                                           | `skills-lock.json`              | 項目の `ref`                          |
| `yokozi-jp/cursor-plugins` | pstack、thermos、cursor-team-kit の `deslop`、`control-cli`、`control-ui`、`kiro-adapter` | `.kiro/.kiro-plugins-installed` | 1行目の `# cursor-plugins <コミット>` |
| なし                       | このリポジトリで書くスキル（`java-best-practices`、検証スキルの `verify-<アプリ名>`）     | `skills-lock.json`              | なし                                  |

導入済みのスキルの一覧は記録が正で、この文書には写さない。
`skills-lock.json` のスキルは、実体を `.agents/skills/` に、Kiro の検出用の相対シンボリックリンクを `.kiro/skills/` に置く。
cursor-plugins のスキルは、実体を `.kiro/skills/` に置く。
同じスキルを両方の記録で管理しない。

`skill-creator` は、`anthropics/skills` の固定コミットに、このリポジトリの配置の規則を足してある。
lock の項目の `note` がそれを示し、`computedHash` は足したあとの内容ハッシュにする。

## cursor-plugins で入るもの

`kiro/install.sh --repo .` は、次のものを `.kiro/` にコピーし、入れたパスと内容ハッシュを `.kiro/.kiro-plugins-installed` に記録する。

- **`skills/`**：pstack、thermos、cursor-team-kit の `deslop`、`control-cli`、`control-ui`、Cursor の名前を Kiro の名前に読み替える `kiro-adapter`。pstack のうち `kiro/install.sh` の `excluded_skills` に載るスキルは入れない（理由は ADR-084）。
- **`agents/`**：`poteto-agent`、`comment-sicko`、thermos の2つのレビュー役。
- **`steering/kiro-adapter.md`**：スキルに従う前に `kiro-adapter` を読むよう案内する。
- **`kiro-permissions.yaml`**：利用者の `~/.kiro/settings/permissions.yaml` の雛形。

## Kiro の権限の雛形を置く

`kiro/install.sh` は、`~/.kiro/settings/` に `permissions.yaml` も `permissions.json` もないときだけ雛形を書く。
あるときは書かず、`permissions.yaml` が雛形と違えば `skip: kept your ... Merge in the rules from ...` と出す。
`.kiro/kiro-permissions.yaml` の2行目のコメント（`kiro/install.sh copies this file ...`）は上流の記述で、この動作を指す。

clone しただけで `kiro/install.sh` を実行していない開発者は、リポジトリの中で次を実行する。
Kiro はエージェントによる `~/.kiro/settings/` への書き込みを拒否するため、エージェントには任せられない。

```bash
mkdir -p ~/.kiro/settings
[ -e ~/.kiro/settings/permissions.yaml ] || cp .kiro/kiro-permissions.yaml ~/.kiro/settings/permissions.yaml
diff .kiro/kiro-permissions.yaml ~/.kiro/settings/permissions.yaml
```

`cp` の行は、`~/.kiro/settings/permissions.yaml` がすでにあると何もせず、何も出力しない。
`diff` が差分を出したら、雛形の `rules` のうち手元のファイルにないものを手で足す。
更新で `.kiro/kiro-permissions.yaml` が変わったときも、同じ `diff` で確かめる。

## Matt Pocock のスキルと skill-creator を導入、更新する

`skills` CLI 1.7.0 で、固定コミットを指定して取得する。
`<取得元>` は `mattpocock/skills` か `anthropics/skills` で、`<ref>` は `skills-lock.json` にある同じ取得元の `ref` である。
`-a kiro-cli` を指定すると、CLI は実体を `.kiro/skills/<スキル名>/` にコピーし、`skills-lock.json` に項目を書く。
そのため、取得したあとで実体を `.agents/skills/` へ移し、`.kiro/skills/` に相対シンボリックリンクを置く。

まだ入れていないスキルは、次の手順で導入する。

```bash
npx skills@1.7.0 add "<取得元>#<ref>" -s <スキル名> -a kiro-cli -y
mv .kiro/skills/<スキル名> .agents/skills/<スキル名>
ln -s ../../.agents/skills/<スキル名> .kiro/skills/<スキル名>
task skills-check
```

スキルが別のスキルを呼ぶ場合は、呼ばれるスキルも同じ手順で導入する。
`SKILL.md` の本文に、呼び出すスキルの名前があるかを確かめる。

入れてあるスキルを取得し直すときは、先に `skills remove` で消す。
`-a` を付けない `remove` は、`.kiro/skills/` のリンク、`.agents/skills/` の実体、`skills-lock.json` の項目を消す。
消さずに `add` すると、CLI はリンクを実体のコピーに置き換え、続く `mv` はそのコピーを古い実体の中へ入れ子にして移す。
`-s` には複数のスキル名を空白で区切って渡せる。

```bash
npx skills@1.7.0 remove -s <スキル名>... -y
npx skills@1.7.0 add "<取得元>#<新しい ref>" -s <スキル名>... -a kiro-cli -y
for s in <スキル名>...; do mv ".kiro/skills/$s" ".agents/skills/$s" && ln -s "../../.agents/skills/$s" ".kiro/skills/$s"; done
task skills-check
```

CLI は `skills-lock.json` を書き戻すとき、`sourceType` が `local` の項目の `source` を `local` から `./local` に変える。
`task skills-check` は `source` を読まないため、この変更は残してよい。
`git diff skills-lock.json` で、取得したスキルの項目のほかに変わったのがこの `source` だけであることを確かめる。

更新するときは、次の順で進める。

1. 新しいコミットを決める。`skills update` で無条件に最新版へ追従しない。
2. 取得元のリポジトリを clone し、`git diff <旧>..<新> -- <lock の skillPath のディレクトリ>` で差分を読む。同梱スクリプト、発火条件、ファイル変更、Issue tracker 操作、コミット操作の変更を確かめる。
3. 取得元ごとの固定コミットを一つに保つため、同じ取得元のスキルをすべて、上の取得し直す手順で新しいコミットから取得する。
4. `skill-creator` は、取得し直すと足した配置の規則と lock の `note` が消える。`git diff .agents/skills/skill-creator` で消えた変更を戻し、`note` を足し直し、`computedHash` を `task skills-check` が表示する内容ハッシュにする。
5. Pull Request の説明に、取得元と旧と新のコミットを書く。

## このリポジトリで書くスキルを記録する

取得元のないスキル（`/create-verification-skill` が作る `verify-<アプリ名>`、`java-best-practices` など）も、`skills-lock.json` に記録する。
スキルに規約を書くときは、[docs文書の作成と変更](../knowledge/documentation-authoring.md)の「文書の構造」に従い、正文を docs に置く。

1. 実体を `.agents/skills/<スキル名>/` に置き、`.kiro/skills/<スキル名>` に相対シンボリックリンクを置く。
   `/create-verification-skill` のように `.kiro/skills/<スキル名>/` に作ったスキルは、次のように移す。

   ```bash
   mv .kiro/skills/<スキル名> .agents/skills/<スキル名>
   ln -s ../../.agents/skills/<スキル名> .kiro/skills/<スキル名>
   ```

2. `skills-lock.json` の `skills` に項目を足す。
   `sourceType` を `local` にし、`note` にスキルの由来を書く。
   取得元がないため、`ref` と `skillPath` は書かない。

   ```json
   "verify-<アプリ名>": {
     "source": "local",
     "sourceType": "local",
     "note": "Generated by /create-verification-skill and kept current by /maintain-verification-skill."
   }
   ```

3. `task skills-check` を実行する。
   `computedHash` がないため内容ハッシュの不一致で失敗し、メッセージに今の内容ハッシュが出る。
   その値を項目の `computedHash` に書き、もう一度 `task skills-check` を実行する。

スキルを直したら（`/maintain-verification-skill` で検証スキルを更新したときを含む）、手順3と同じく `computedHash` をメッセージの値に直す。

## pstack と thermos を導入、更新する

固定コミットは `.kiro/.kiro-plugins-installed` の1行目にある。
手順は、cursor-plugins の checkout を `~/cursor-plugins` に置く前提で書く。

1. checkout がなければ clone する。

   ```bash
   [ -d ~/cursor-plugins ] || git clone https://github.com/yokozi-jp/cursor-plugins.git ~/cursor-plugins
   ```

2. 旧のコミットを読み、新しいコミットを checkout する。

   ```bash
   old="$(head -1 .kiro/.kiro-plugins-installed | cut -d' ' -f3)"
   git -C ~/cursor-plugins fetch && git -C ~/cursor-plugins checkout <新しいコミット>
   ```

3. 入るファイルの差分を読む。

   ```bash
   git -C ~/cursor-plugins diff "$old"..<新しいコミット> -- pstack thermos cursor-team-kit/skills/{deslop,control-cli,control-ui} kiro
   ```

   同梱スクリプト、発火条件（`description`）、権限（`kiro/permissions.yaml`、エージェントの `tools` と deny）、ファイルを書くコマンド、コミットや push の指示、新しく入るスキルを確かめる。
   入れないスキルの一覧は `kiro/install.sh` の `excluded_skills` だけに置き、この文書には写さない。
   入れないスキルを増やすときは、ADR で決めてから `excluded_skills` に足す。
   ADR-084 が Proposed の間は、ADR-084 の Decision を書き直してよい。
   このリポジトリの規約と食い違う指示が増えていれば、[RGDD の開発フロー](rgdd.md)のフェーズ3の一覧に足す。

4. 入れて、検査する。

   ```bash
   ~/cursor-plugins/kiro/install.sh --repo .
   task skills-check
   ```

   `kiro/install.sh` は、cursor-plugins の checkout に未コミットの変更があると止まる。
   前回入れたファイルは新しい版に置き換わる。
   `skip: ... already exists` と出たものは入っていないので、同じ名前のファイルを確かめる。
   `skip: kept your ... permissions.yaml` と出たら、「Kiro の権限の雛形を置く」の `diff` で確かめる。

5. `.kiro/` の変更を commit し、Pull Request の説明に `cursor-plugins <旧>..<新>` を書く。

## 編集しない

- `.kiro/.kiro-plugins-installed` に載るファイルと、このファイル自身を編集しない。次の更新で上書きされる。変更は cursor-plugins の `kiro/` か上流で行う。エージェントの書き込みは [Kiro のフック](kiro-hooks.md)が拒否する。
- `.agents/skills/` の上流のファイルを編集しない。例外は `skill-creator` だけで、lock の `note` に理由を書き、`computedHash` を変えたあとの内容ハッシュにする。

## 検証

導入、更新、記録のあとは `task skills-check` を実行する。
検査の範囲は [Lintとテストのリファレンス](../tooling/lint-and-test.md) にある。
