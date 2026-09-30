---
type: Convention
title: steering、docs、iwe の役割分担
description: プロジェクトの知識を steering、docs、iwe の3層に分ける規約。各層に書くもの、依存方向、領域フォルダと index.md の形式、文書と steering の大きさ、frontmatter の type、検査、新しい領域の追加手順を定める。docs や steering を追加、分割、移動するとき、どこに何を書くか迷ったときに読む。
tags: [convention, documentation, steering, okf, knowledge-management]
---

# steering、docs、iwe の役割分担

知識は3層に分け、依存は steering から docs への一方向にする。
規約の正文は docs に置き、steering は「いつ、どの docs を読むか」だけを持つ。ただし、エージェント設定ではルートの `AGENTS.md` が steering を兼ねる。
docs は領域ごとのフォルダに分け、各フォルダの index.md を入口にする。
この分担は [ADR-038](../adr/ADR-038-route-steering-to-docs-knowledge.md) で決定している。

## 3つの層

- **steering**（`.kiro/steering/`）：Kiro のナビゲーション。いつ docs を読むか、どの領域ならどの index.md を読むか、行動原則、全タスクで守る短いルール、Kiro 固有の使い方を書く。
- **docs**（`docs/`）：プロジェクトの知識。仕様、アーキテクチャ、コーディング規約の詳細、ADR、ドメイン知識、Runbook、API と DB のルール、日時や認証などの方針の正文を書く。
- **iwe**：docs を検索し、リンクを追跡するエンジン。グラフは `docs/` の中で閉じる。

steering に書いた知識は iwe の検索とリンク検査の対象にならない。
そのため、短いルールを steering に置く場合も、正文は docs に置いて steering からパスを示す。

## 依存方向

- docs から `.kiro/` 配下へリンクしない。
- steering から docs を指すときは、パスを文字列（`docs/backend/index.md` など）で書く。
- steering で `#[[file:docs/...]]` を使わない。Kiro CLI はすべての steering を常時読み込むため、展開された docs が毎回コンテキストに入る可能性がある。
- steering からソースコードや設定ファイルを `#[[file:...]]` で参照するのはよい。

## 読む流れ

エージェントは次の順で docs を読む。

1. steering（エージェント設定ではルートの `AGENTS.md`）で、作業する領域と読むべき index.md を決める。
2. 領域の index.md で、「いつ読むか」が作業に該当する行の文書を選ぶ。
3. 必要なら iwe（`iwe_retrieve`、`iwe_find`）でリンクをたどり、関連する文書だけを読む。

index に該当する行がなければ、`iwe_find` の全文検索で探す。
それでも見つからなければ、推測で進めず利用者に確認する。
作業で判明した知識は、docs への追加を提案する。

## 領域フォルダ

docs は領域ごとのフォルダ（`docs/backend/`、`docs/database/` など）に分ける。
各領域は次の3つをそろえる。

- **`docs/<領域>/index.md`**：その領域の全文書へのリンクを並べた入口。
- **領域の文書**：1テーマ1ファイルの規約、アーキテクチャ、Runbook など。
- **`.kiro/steering/<領域>.md`**：その領域のファイルに `fileMatch` する薄い steering。行動指針と、ケースごとに読む文書の対応だけを書く。

`docs/index.md` は、各領域の index.md へ「いつ読むか」を添えてリンクする。
領域に属さない単独の文書（リリース管理など）も、`docs/index.md` から同じ形式でリンクする。
`docs/knowledge/` の領域では、常時読み込みの steering `docs-navigation` が領域の steering を兼ねる。
`docs/agents/` の領域では、ルートの `AGENTS.md` が領域の steering を兼ねる。

## index.md の形式

index.md は OKF の予約ファイルであり、frontmatter を持たない（`docs/index.md` だけは `okf_version` を持つ）。
H1 の見出しと、次の形式の箇条書きだけを書く。

```markdown
- [タイトル](file.md)：いつ読むか
```

「いつ読むか」には、その文書を開くべき作業や状況を書く。
タイトルの言い換えにしない。
どの index.md からもリンクされない文書を残さない。

## 文書の書き方

- 1テーマ1ファイルにし、200行以内を目安にする。
- 冒頭にルールの要約を1〜4行で置き、詳細はその後に書く。
- 規約文書には現行ルールと検査方法を書き、理由は ADR へリンクする。理由を規約文書に重複して書かない。
- エージェントが繰り返す手順は Runbook（`runbook-<内容>.md`）を正本にする。skill を作る場合は Runbook を指すだけにする。

frontmatter には `type`、`title`、`description`、`tags` を置く。
`description` には、何を定める文書かと、いつ読むかを書く。
`type` は次の語彙から選ぶ。

- **Convention**：守るべき規約。
- **Architecture**：構造と依存の説明。
- **Runbook**：手順。
- **Reference**：コマンドや設定の早見表。
- **Domain**：業務知識。
- **Architecture Decision Record**：ADR。

文書の鮮度管理（OKF の `verified`、`sources`、`stale_after`）は、古い文書が問題になるまで導入しない。

## steering の書き方

- 1ファイル150行、合計500行以内を目安にする。
- 領域の steering は30行程度にとどめ、規約の本文を書かない。
- 全タスクで守る短いルール（日時の扱いなど）だけは、常時読み込みの steering に要約として置いてよい。その場合も正文は docs に置き、steering からパスを示す。

## ADR との関係

ADR は判断の履歴であり、docs の規約文書は現在の状態を表す。
ADR が Accepted または Superseded になったら、対応する規約文書を同じ変更で更新する。
ADR の運用は [ADR の運用ルール](../adr/conventions.md) に従う。

## 検査

`task okf-check` が次を検査する。
依存方向とパスの実在は失敗、行数は警告にする。

- OKF の適合（frontmatter と index.md の形状）。
- どこからもリンクされない孤立文書。
- docs から `.kiro/` 配下へのリンク。
- steering の `#[[file:docs/...]]`。
- steering が指す docs のパスが実在すること。
- docs の文書が200行、steering が1ファイル150行、合計500行を超えていないこと。

同じ検査を Lefthook の pre-commit と CI（`okf-validate.yml`）が実行する。
詳細は [Lint・テストのリファレンス](../lint-and-test.md) を参照する。

## 新しい領域を追加する手順

1. `docs/<領域>/` を作り、最初の文書と index.md を置く。
2. `docs/index.md` に、領域の index.md へのリンクを「いつ読むか」付きで1行加える。
3. `.kiro/steering/<領域>.md` を作り、その領域のファイルに `fileMatch` させる。ルートの `AGENTS.md` が領域の steering を兼ねる場合は省く。
4. steering `docs-navigation` の「領域ごとの入口」の表に1行加える。
5. `task okf-check` を実行する。
