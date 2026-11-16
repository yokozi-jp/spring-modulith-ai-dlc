---
type: Runbook
title: RGDD の開発フロー
description: README、grill-with-docs、poteto-mode、thermos、検証スキルの5つのフェーズで開発し、各フェーズのコンテキストを育てる手順と、pstack と thermos の導入と更新。
tags: [runbook, workflow, agent-skills, pstack, thermos]
---

# RGDD の開発フロー

## 要約

- 開発は README駆動 with Grill Driven Development（RGDD）の5つのフェーズで進める（[ADR-084](../adr/ADR-084-adopt-rgdd-with-pstack-and-thermos.md)）。
- README、`GLOSSARY.md`、ADR をコードより先に置き、エージェントにそれらを読ませながら開発する。
- 各フェーズは、前のフェーズが残した文書を読み、書き足すか、確かめて直す。
- pstack と thermos は cursor-plugins の `kiro/install.sh` で入れ、`.kiro/` の中では編集しない。

## 5つのフェーズ

```text
1. README            開発者が、ユーザーから見た使い方を書く
  ↓
2. /grill-with-docs  用語を GLOSSARY.md に、判断を ADR に残す
  ↓
3. /poteto-mode      コードと Pull Request を作る
  ↓
4. /thermos          差分を2つの観点で監査し、指摘を直す
  ↓
5. /create-verification-skill  最初の機能が動いたら、検証スキルと Feature Map を作る
  ↓
/retro（タスクの区切りで、セッションを消去する前に）
```

| フェーズ                        | 読むもの                                | 書き足すもの、確かめて直すもの   |
| ------------------------------- | --------------------------------------- | -------------------------------- |
| 1. README                       | なし                                    | 誰が、何のために、どう使うか     |
| 2. `/grill-with-docs`           | README                                  | `GLOSSARY.md` の用語、ADR        |
| 3. `/poteto-mode`               | README、`GLOSSARY.md`、ADR、docs の規約 | コード、Pull Request             |
| 4. `/thermos`                   | フェーズ3の差分                         | 差分の問題。直してからマージする |
| 5. `/create-verification-skill` | 動くアプリ                              | Feature Map、確かめ方            |

### 1. README

開発者が、想定するユーザーに向けて使い方を書く。
実装の話はまだ書かない。
このフェーズの README は、フェーズ2で質問を重ねる題材になる。

### 2. /grill-with-docs

開発者が `/grill-with-docs` を名前で呼ぶ。
`/grilling` が未決事項を前提の順に質問し、`/domain-modeling` が用語を `GLOSSARY.md` に書き、ADR にすべき判断を提案する。
ADR は [ADR の運用ルール](../adr/conventions.md)の基準と書式に従う。

仕様と実装チケットに分けるときは、同じセッションで `/to-spec`、`/to-tickets` を続ける。
一つのセッションに収まらない仕事は `/wayfinder`、他の担当者だけが答えられる判断は `/to-questionnaire` を使う。
これらのスキルの扱いは [Matt Pocock スキル運用](matt-pocock-skills/workflow.md)に従う。

### 3. /poteto-mode

`/poteto-mode` が作業の種類に合うプレイブック（機能追加、バグ修正、リファクタリングなど）を選び、Pull Request まで進める。
チケットごとに新しいセッションで始め、チケットの内容を最初のメッセージに渡す。

仕組みを知りたいときは `/how`、設計の理由を知りたいときは `/why`、最近の経緯を集めたいときは `/recall` を使う。
`/why` は、コミットと Pull Request の記録に加えて ADR を読む。

### 4. /thermos

`/thermos` が、正しさとセキュリティの監査と、保守性の監査を、2つのサブエージェントで並列に行い、指摘を一つの一覧にまとめる。
`/thermos` はコードを直さないので、指摘を直してからマージする。
このリポジトリの規約に照らすレビューは、steering `code-review` と [コードレビュー](../code-review/index.md) が担う。

レビューする Pull Request のブランチをチェックアウトし、その中で Kiro CLI を起動して実行する。
対象から `.kiro/` の中の `kiro/install.sh` で入れたファイル（`.kiro/.kiro-plugins-installed` に載るもの）を外し、重点を置くファイルを伝える。

```text
/thermos PR #<番号>（<ブランチ> と main の差分）をレビューして。
.kiro/.kiro-plugins-installed に載るファイルは上流のままなので対象外。
重点は <ファイル>。
```

変更の種類によって、次のレビューを足す。
どちらも指摘を報告するだけで、コードは直さない。

- **検査や境界の仕組みを変えたとき**：`/interrogate` に意図を伝え、サブエージェントに実際に壊させる。実行して失敗を示せた指摘だけが残る。

  ```text
  /interrogate <ディレクトリ> の変更。意図は「<満たすべき振る舞い>」こと。壊せるか実際に動かして確かめて。
  ```

- **削除や参照先の変更など、影響が差分の外に及ぶとき**：`/blast-radius` で、差分が触れていない場所で壊れるものを探す。

  ```text
  /blast-radius PR #<番号>。消したファイルを参照している箇所が残っていないか、検査の範囲が変わって見逃しが出ないか。
  ```

Kiro では、これらのサブエージェントは呼び出し元のチャットと同じモデルで動く。

### 5. /create-verification-skill

最初の機能が動いたら、`/create-verification-skill` で検証スキルと Feature Map を作る。
以降はエージェントが毎回のタスクでその検証スキルを使って変更を確かめ、`/maintain-verification-skill` を少なくとも1日1回実行して検証スキルと Feature Map を最新に保つ。

### 振り返り

タスクの区切りで `/retro` を実行する。
手順と還元先は [Matt Pocock スキル運用](matt-pocock-skills/workflow.md)の「振り返り」に従う。

## pstack と thermos

### 何が入っているか

[yokozi-jp/cursor-plugins](https://github.com/yokozi-jp/cursor-plugins) の `kiro/install.sh` が、次のものを `.kiro/` にコピーする。

- `skills/`：pstack（`setup-pstack` を除く）、thermos、cursor-team-kit の `deslop`、`control-cli`、`control-ui`、Cursor の名前を Kiro の名前に読み替える `kiro-adapter`
- `agents/`：`poteto-agent`、`comment-sicko`、thermos の2つのレビュー役
- `steering/kiro-adapter.md`：スキルに従う前に `kiro-adapter` を読むよう案内する
- `kiro-permissions.yaml`：利用者の `~/.kiro/settings/permissions.yaml` の雛形

入れたファイルと内容ハッシュは `.kiro/.kiro-plugins-installed` に記録される。
モデルはエージェントファイルで決まるので、`/setup-pstack` は使わない。

### 使い始める

clone したら、リポジトリの中で1回だけ実行する。

```bash
mkdir -p ~/.kiro/settings && [ -e ~/.kiro/settings/permissions.yaml ] || cp .kiro/kiro-permissions.yaml ~/.kiro/settings/permissions.yaml
```

### 更新する

```bash
git -C ~/cursor-plugins pull
~/cursor-plugins/kiro/install.sh --repo .
task skills-check
```

前回入れたファイルが新しい版に置き換わる。
`skip: ... already exists` と出たものは入っていないので、同じ名前のファイルを確かめる。
変更を commit し、Pull Request にする。

### 禁止事項

- `.kiro/.kiro-plugins-installed` に載るファイルを編集しない。`task skills-check` が失敗し、次の更新で上書きされる。変更は cursor-plugins の `kiro/` か上流で行う。
- 同じスキルを `skills-lock.json` と `.kiro/.kiro-plugins-installed` の両方で管理しない。

## 検証

導入と更新のあとは `task skills-check` を実行する。
このタスクは、`.kiro/.kiro-plugins-installed` の各ファイルが記録した内容ハッシュと一致するかと、`skills-lock.json` のスキルの整合を検査する。
検査の範囲は [Lint・テストのリファレンス](../tooling/lint-and-test.md) にある。
