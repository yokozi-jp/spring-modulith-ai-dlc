---
type: Convention
title: docs文書の作成と変更
description: docs配下のMarkdownを1ファイル1責務で作成、編集、移動、分割するときの責務、手段、配置、index、frontmatter、キー、検査、新しい領域の追加を定める規約。docsの文書へ変更を加える前に読む。
tags: [convention, documentation, iwe, okf]
---

# docs文書の作成と変更

`docs/`はiweのナレッジグラフとして管理し、新規文書はiwe MCPを通して作成する。
既存文書はファイル編集ツールで変更し、移動後はリンクを手動で直す。
文書は領域の`index.md`へ接続し、変更後は`task okf-check`でリンクと形式を検証する。

## 対象

この規約は、エージェントが`docs/`配下のMarkdownを新規作成、編集、移動、分割するときに適用する。
新しいdocs領域を追加するときにも適用する。
人が文書を手で書く方法は制約しない。

`docs/`の文書間リンクと参照はiweが追跡する。
ファイルを直接作ると、作成時点ではグラフのリンク切れや孤立を検出できないため、新規作成の経路を固定する。

## 新規文書の作成

`docs/`配下の新規文書はiwe MCPサーバーを通して作る。
Kiroでは`iwe_create`にキーと本文を渡して作成する。
作成結果に返るリンク切れ、孤立文書、類似ページの警告をその場で確認する。

リトライで同じ文書を二重作成しないよう、既存文書を変更しない再試行では`if_exists: skip`を使う。
既存文書を意図して置き換える場合は、後述する破壊的操作の条件を満たす。

## 既存文書の変更

既存文書の編集、移動、分割には、iweの`rename`、`update`、`extract`、`inline`を使わない。
これらの操作は対象文書とリンク元をiweの書式で書き直し、一文一改行を段落へ連結したり、frontmatterの`tags`をブロック形式へ変えたりするためである。

既存文書はファイル編集ツールで書き換える。
文書の移動には`git mv`を使い、リンク元の相対パスを手動で修正する。
編集または移動の後は`task okf-check`を実行する。

## 領域と入口

知識をsteering、docs、iweのどこへ置くかは[steering、docs、iweの役割分担](knowledge-architecture.md)に従う。
docsは`docs/backend/`や`docs/database/`のように領域ごとのフォルダへ分ける。
各領域には、その領域の全文書へのリンクを持つ`docs/<領域>/index.md`と、1責務1ファイルの文書を置く。

通常の領域には、対象ファイルから領域の`index.md`へ案内する`.kiro/steering/<領域>.md`も置く。
`docs/knowledge/`では`docs-navigation`が、`docs/agents/`ではルートの`AGENTS.md`が領域のsteeringを兼ねるため、領域専用のsteeringを作らない。

`docs/index.md`から各領域の`index.md`へ「いつ読むか」を添えてリンクする。
領域に属さない単独文書も`docs/index.md`から同じ形式でリンクする。
どの`index.md`からもリンクされない文書を残さない。

## index.mdの形式

`index.md`はOKFの予約ファイルであり、frontmatterを持たない。
ただし、`docs/index.md`だけは`okf_version`を持つ。

H1の見出しと、次の形式の箇条書きだけを書く。

```markdown
- [タイトル](file.md)：いつ読むか
```

「いつ読むか」には、その文書を開くべき作業や状況を書く。
タイトルの言い換えにしない。

## 文書の構造

1ファイル1責務を主原則とし、「この文書を読めば何が決まるか」を一つの問いで言える単位にする。
frontmatterの`type`と本文を照合し、異なるtypeの責務を一つの文書へ混ぜない。
250行は責務混在を見直す合図とする。
250行を超えても責務が一つなら維持し、250行以下でも責務が混在すれば既存文書への移動または分割を検討する。
冒頭にルールの要約を1行から4行で置き、詳細はその後に書く。
規約文書には現行ルールと検査方法を書き、理由はADRへリンクする。
理由を規約文書へ重複して書かない。

エージェントが繰り返す手順はRunbookを正文にする。
skillを作る場合は、そのRunbookを参照するだけにする。

日本語の文章は[日本語技術文書の文章規範](../writing/japanese-tech-writing.md)に従う。
ADRの作成条件、採番、書式、ライフサイクルは[ADRの運用ルール](../adr/conventions.md)に従う。

## frontmatter

`index.md`以外の文書には、`type`、`title`、`description`、`tags`を置く。
`description`には、何を定める文書かと、いつ読むかを書く。
`type`は本文の主責務に一致させ、次の語彙から選ぶ。

- **Convention**：守るべき規約。
- **Architecture**：構造と依存の説明。
- **Runbook**：手順。
- **Reference**：コマンドや設定の早見表。
- **Domain**：業務知識。
- **ADR**：Architecture Decision Record。

文書の鮮度管理に使うOKFの`verified`、`sources`、`stale_after`は、古い文書が問題になるまで導入しない。

## キー

文書のキーは、タイトルの言い回しではなく、エンティティ名やADR番号など後から変わりにくい情報から導く。
サブディレクトリを含むキーを使ってよい。

ADRのキーは`adr/ADR-013-standardize-http-api-contracts`のように、`adr/`の下へ採番と内容を並べる。
新しいADRも既存形式に合わせる。

既存キーと衝突した場合は、`fail`または`skip`として指定された衝突時の動作を尊重する。
再試行を冪等にするときは`skip`を選ぶ。

## 作成後の警告

作成または更新の結果に含まれるリンク切れ、孤立文書、類似ページの警告は、そのセッション内で解消する。
警告を残したまま作業を終了しない。

新しい文書が孤立した場合は、親となる`index.md`または本文からリンクする。
ADRを作成した場合は`docs/adr/index.md`の一覧へ加える。

`docs/`外のソースコードや設定ファイルへの相対リンクは、実在していてもiweがリンク切れとして報告することがある。
報告されたパスが実在することを確認できた場合、この警告は解消対象から除いてよい。

## 破壊的操作

上書き、`normalize`、`delete`は、対象がコミット済みで取り消せる状態に限って実行する。
これらの操作はリンクを追従して複数文書を書き換えるためである。

`iwe normalize`は`docs/`の全文書を機械的に書き換えるため、PreToolUseフック`block-iwe-normalize`で通常実行を拒否する。
Kiro CLI用の`.kiro/hooks/block-iwe-normalize.json`と判定本体の`.kiro/hooks/block-iwe-normalize.sh`が、CLI経路とMCP経路を検出して`exit 2`を返す。

意図して`iwe normalize`を実行する場合は、先に`docs/`をコミットする。
その後、環境変数`IWE_ALLOW_NORMALIZE=1`を設定してKiroを再起動する。

## 新しい領域を追加する手順

1. `docs/<領域>/`を作り、最初の文書と`index.md`を置く。
2. `docs/index.md`に、領域の`index.md`へのリンクを「いつ読むか」付きで1行加える。
3. [steeringの書き方](steering-authoring.md)に従い、`.kiro/steering/<領域>.md`を作って対象ファイルへ`fileMatch`させる。ルートの`AGENTS.md`が領域のsteeringを兼ねる場合は省く。
4. steering`docs-navigation`の「領域ごとの入口」に1行加える。
5. `task okf-check`を実行する。

## 検証

変更後は`task okf-check`を実行し、OKF形式、docs内部のリンク切れ、孤立文書、steeringのfrontmatterとdocsへの参照を確認する。
Markdownの変更では、リポジトリで設定されたMarkdown lintも実行する。
検査コマンドと検査範囲は[Lint・テストのリファレンス](../tooling/lint-and-test.md)を参照する。
