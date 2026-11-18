---
type: Runbook
title: RGDD の開発フロー
description: README、grill-with-docs、poteto-mode、thermos、検証スキルの5つのフェーズと振り返りで開発する手順と、外部スキルの記述がリポジトリの規約と食い違うときの扱い。機能を作り始めるとき、pstack と thermos を使うとき、タスクの区切りで振り返るときに読む。
tags: [runbook, workflow, agent-skills, pstack, thermos]
---

# RGDD の開発フロー

## 要約

- 開発は RGDD（README駆動 with Grill Driven Development）の5つのフェーズで進め、タスクの区切りで `/retro` を実行する（[ADR-084](../adr/ADR-084-adopt-rgdd-with-pstack-and-thermos.md)）。
- 各フェーズは、前のフェーズが残した README、`GLOSSARY.md`、ADR を読み、書き足すか、確かめて直す。
- 外部から入れたスキル（`skills-lock.json` で取得元を持つものと、`.kiro/.kiro-plugins-installed` に載るもの）の記述が docs の規約や ADR と食い違うときは、docs の規約と ADR に従う。食い違いの例はフェーズ3にある。
- スキルの導入と更新は [スキルの取得元と更新](skill-sources.md) に従う。

## 5つのフェーズ

各フェーズで読む文書と、書き足すか確かめて直す文書は次のとおり。

| フェーズ                        | 読むもの                                | 書き足すもの、確かめて直すもの   |
| ------------------------------- | --------------------------------------- | -------------------------------- |
| 1. README                       | なし                                    | 誰が、何のために、どう使うか     |
| 2. `/grill-with-docs`           | README                                  | `GLOSSARY.md` の用語、ADR        |
| 3. `/poteto-mode`               | README、`GLOSSARY.md`、ADR、docs の規約 | コード、Pull Request             |
| 4. `/thermos`                   | フェーズ3の差分、`docs/code-review/`    | 差分の問題。直してからマージする |
| 5. `/create-verification-skill` | 動くアプリ                              | 検証スキル、Feature Map          |

### 1. README

開発者が、想定するユーザーに向けて使い方を書く。
実装の話はまだ書かない。
このフェーズの README は、フェーズ2で質問を重ねる題材になる。

### 2. /grill-with-docs

開発者が `/grill-with-docs` を名前で呼ぶ。
`/grilling` が未決事項を前提の順に質問し、`/domain-modeling` が用語を `GLOSSARY.md` に書き、ADR にすべき判断を提案する。
ADR は [ADR の運用ルール](../adr/conventions.md)の基準と書式に従う。

仕様と実装チケットに分けるときは、同じセッションで `/to-spec`、`/to-tickets` を続け、対話で決めた前提を仕様とチケットへ引き継ぐ。
チケットには単独で実装できる情報を持たせ、前のチケットの会話へ依存させない。
外部から来た Issue、一つのセッションに収まらない仕事、試作で確かめる問いなど、この流れに乗らない作業の入口は [Matt Pocock スキル運用](matt-pocock-skills/workflow.md)の「例外時の入口」にある。

### 3. /poteto-mode

`/poteto-mode` が作業の種類に合うプレイブック（機能追加、バグ修正、リファクタリングなど）を選び、Pull Request まで進める。
チケットごとに新しいセッションで始め、チケットの内容を最初のメッセージに渡す。

仕組みを知りたいときは `/how`、設計の理由を知りたいときは `/why`、最近の経緯を集めたいときは `/recall` を使う。
`/why` は、コミットと Pull Request の記録に加えて ADR を読む。

pstack のプレイブックには、このリポジトリの規約と食い違う指示がある。
次の場面では、プレイブックではなくリポジトリの規約に従う。

- **Pull Request の作成**：`opening-a-pr.md` と `multi-phase-plan.md` は Draft にせずに開くよう指示する。このリポジトリでは Draft で作り、`/thermos` を実行して指摘を直し、CI と差分を確かめてから Ready for review にする（[Draftからマージまで](../pull-request/review-request-and-merge.md)）。
- **rebase した履歴の push**：`babysit.md`、`autopilot-full.md`、`shipping.md` は `git push --force-with-lease` を使う。権限の雛形（`.kiro/kiro-permissions.yaml`）は `git push *--force*` を拒否するため、エージェントは force push をせず、履歴の書き換えが要る場面では止めて利用者に報告する。

### 4. /thermos

`/thermos` が、正しさとセキュリティの監査と、保守性の監査を、2つのサブエージェントで並列に行い、指摘を一つの一覧にまとめる。
2つの監査の観点は汎用で、このリポジトリの docs の規約を読まない。
そのため、`/thermos` を実行したセッションで、差分を [レビュー観点と参照する規約](../code-review/review-viewpoints.md) に照らして見直し、見つけた問題を同じ一覧に加える。
`/thermos` はコードを直さないので、一覧の指摘を直してからマージする。

レビューする Pull Request のブランチをチェックアウトし、その中で Kiro CLI を起動して実行する。
対象から `.kiro/.kiro-plugins-installed` に載るファイルを外し、重点を置くファイルを伝える。

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

フェーズ4を終えた時点で、`.kiro/skills/` にこのリポジトリの検証スキル（`verify-<アプリ名>`）がなければ、`/create-verification-skill` で検証スキルと Feature Map を作る。
作った検証スキルは、[スキルの取得元と更新](skill-sources.md)の「このリポジトリで書くスキルを記録する」に従って `skills-lock.json` に記録する。
以降は、エージェントが毎回のタスクでその検証スキルを使って変更を確かめ、`/maintain-verification-skill` で検証スキルと Feature Map を最新に保つ。
`/maintain-verification-skill` を実行する頻度は [#366](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/366) で決める。

## 振り返り

`/retro` を実行する条件は、常に読み込まれる `AGENTS.md` の「学びの還元」にある。
対象のセッションを消去する前に、同じセッションで実行する。

消去してしまった場合は、そのセッションのログを指定して実行する。

`/retro` は改善候補を提示するだけで、何も変更しない。
利用者が選んだ候補だけを、[学びを仕組みへ還元し続ける](../principles/continuous-reinforcement.md)の5つの層の優先順位に従って還元する。
次の読み替えで、上流の記述をこのリポジトリに合わせる。

- 上流が `CODING_STANDARDS.md` に置く判断を要する規約は、該当する領域の docs に置き、`docs/code-review/review-viewpoints.md` から辿れるようにする。`CODING_STANDARDS.md` は作らない。
- 機械的な誤りは、`docs/tooling/lint-and-test.md` の既存の検査から探し、接続されていない検査を先に指摘する。
- steering と `AGENTS.md` は案内に限る。規約の本文は docs へ置く（[ADR-038](../adr/ADR-038-route-steering-to-docs-knowledge.md)）。
- 文書を書く前に、`/writing-for-agents` の基準に加えて、`docs/knowledge/documentation-authoring.md` と `docs/knowledge/steering-authoring.md` を読む。
- 戻しにくい還元は、`docs/adr/conventions.md` の基準に当たれば ADR を起こす。
- 規約や検査を追加する候補には、リポジトリに既にある違反の一覧を添える。
