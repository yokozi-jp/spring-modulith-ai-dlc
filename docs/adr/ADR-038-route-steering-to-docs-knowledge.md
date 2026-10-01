---
type: ADR
title: 'ADR-038: steering をナビゲーションに限定し、規約の正文を docs/ に置く'
description: steering をエージェント向けのナビゲーションに限定し、規約の正文を docs/ に集約して、steering から docs/ への一方向の依存とする決定。
tags: [adr, documentation, okf, steering, knowledge-management]
---

# ADR-038: steering をナビゲーションに限定し、規約の正文を docs/ に置く

## Status

Accepted

## Date

2026-09-30

## Context

規約の正文が `.kiro/steering/` と `docs/` に分かれている。
テスト、日時、Dockerfile、Compose、Taskfile の規約は steering にだけあり、アーキテクチャ、DB、Lint の規約は docs にだけある。
ADR の運用ルールは両方にあり、内容が重複している。
docs 側から steering を参照する箇所もあり、依存が双方向になっている。

Kiro CLI では steering の `fileMatch` が効かず、`.kiro/steering/` のすべてのファイルが常時読み込まれる。
steering の合計は約 1,100 行あり、どのタスクでもこの全量がコンテキストに入る。
一方、[ADR-036](ADR-036-adopt-okf-for-docs-knowledge-bundle.md) で `docs/` を OKF バンドルにし、iwe で検索とリンクの追跡ができるようになった。
iwe のグラフは `docs/` だけを対象にするため、steering に置いた知識は検索もリンク検査もできない。

docs 側には、一度に読むには大きい文書がある一方、行数が少なくても複数の責務が混在し得る。
エージェントが必要な知識だけを選べるよう、文書を責務単位に保ち、行数は責務混在を見直す合図として扱う必要がある。

## Decision

知識を三つの層に分ける。
**steering** は Kiro のナビゲーションとし、いつ docs を読むか、どの領域ならどの `index.md` を読むか、行動原則、全タスクで守る短いルール、Kiro 固有の使い方だけを書く。
**docs** はプロジェクトの知識とし、仕様、アーキテクチャ、コーディング規約の詳細、ADR、ドメイン知識、Runbook、API と DB のルール、日時や認証などの方針の正文を置く。
**iwe** は docs を検索し、リンクを追跡するエンジンとする。

依存は steering から docs への一方向とする。
docs から `.kiro/steering/` を参照しない。
steering から docs へは、`#[[file:...]]` で本文を展開せず、パスを文字列で示す。

エージェントは steering から該当領域の `docs/<領域>/index.md` を読み、iwe で検索とリンクの追跡を行い、必要な文書だけを読む。
index が該当しない場合は `iwe_find` で全文検索し、それでも見つからなければ推測で進めずに利用者へ確認する。
新しく判明した知識は docs への追加を提案する。

運用のため、次を定める。

- steering は 1 ファイル 150 行、合計 500 行を上限の目安とする。
- 文書は1ファイル1責務を主原則とし、「この文書を読めば何が決まるか」を一つの問いで言える単位にする。250行は責務混在を見直す目安とし、超過だけを分割理由にしない。250行以下でも責務が混在すれば移動または分割を検討する。
- 各領域の `index.md` は目次とルーターを兼ね、各行に「いつ読むか」を書く。どの index からもリンクされない文書を残さない。
- 規約文書には現行ルールと検査方法を書き、理由は ADR へリンクする。ADR が Accepted または Superseded になったら、対応する規約文書を更新する。
- 各文書の冒頭にルールの要約を置き、詳細はその後に書く。
- frontmatterの`type`を固定の語彙（`Convention`、`Architecture`、`Runbook`、`Reference`、`Domain`、`ADR`）から選び、本文の主責務に一致させる。`iwe_find`の絞り込みにも使う。
- エージェントが繰り返す手順は docs の Runbook を正本とし、skill は Runbook を指すだけにする。
- 依存方向、steeringからdocsへのパスの実在、非index文書の250行超は`task okf-check`で検査する。依存方向とパスの実在は失敗とし、250行超は責務混在を見直す警告とする。`type`と本文の責務一致はレビューで確認する。

OKF の `verified` と `sources` による鮮度管理は、古い文書が問題になるまで導入しない。

移行は領域ごとに段階的に行う。
iwe の rename と extract はリンク元を含む文書全体を iwe の書式で書き直すため使わず、`git mv` とファイル編集でリンクを直し、`task okf-check` で確かめる。
領域ごとに `docs/<領域>/index.md` と、その領域のファイルに fileMatch する薄い steering を置く。

## Consequences

### Positive

- 常時読み込まれる steering が小さくなり、タスクに無関係な規約がコンテキストを占めなくなる。
- 規約の正文が docs に一本化され、iwe の検索、リンク検査、OKF の検証の対象になる。
- 依存方向とパスの実在を検査するため、steering と docs のリンク切れや循環参照を CI で検出できる。
- 文書の責務が一つに絞られるため、エージェントが必要な文書を選びやすい。

### Negative

- エージェントが index を読み忘れると、規約を参照せずに作業する余地が残る。steering の常時読み込みで規約全文を与えていた現状より、遵守の保証は弱くなる。
- 規約を読むたびに index を経由するため、ツール呼び出しが増える。
- 既存の steering と docs の移動と分割に作業量がかかり、移行中は新旧の置き場所が混在する。
- 責務混在は機械判定できないため、250行以下の文書もレビューしなければならない。

### Neutral

- steering に残す短いルールと docs の正文が重なる箇所は、steering 側を要約にとどめ、docs へのリンクを添える。
- Kiro IDE では `fileMatch` が効くため、steering の inclusion 設定は残す。

## Alternatives Considered

### 選択肢1: steering から `#[[file:docs/...]]` で docs の本文を展開する

- **Description**：steering の本文を docs への file 参照にし、正文は docs に置く。
- **Pros**：IDE では対象ファイルを開いたときに規約全文が確実に読み込まれる。
- **Cons**：Kiro CLI が展開する場合、全 steering が常時読み込まれるため全トピックが毎回コンテキストに入り、文書を小さく分けた効果がなくなる。CLI が展開するかは確認していない。

### 選択肢2: 規約を steering に集約する

- **Description**：docs にある規約も steering へ移し、エージェントには常に全文を読ませる。
- **Pros**：エージェントが規約を読み忘れない。
- **Cons**：常時読み込みの量がさらに増える。iwe の検索、リンク検査、OKF の検証の対象外になる。人が読む文書と重複する。

### 選択肢3: 現状の混在を維持する

- **Description**：規約の置き場所を領域ごとの慣習に任せる。
- **Pros**：移行作業が要らない。
- **Cons**：正文の所在が決まらず、重複と双方向の依存が残る。

## References

- [ADR-036: docs/ を OKF v0.2 バンドルとして構成する](ADR-036-adopt-okf-for-docs-knowledge-bundle.md)
- [ADR の運用ルール](conventions.md)
- [Kiro Docs, Steering](https://kiro.dev/docs/steering/)
