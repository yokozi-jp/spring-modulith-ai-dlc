---
type: Convention
title: docs文書の作成と変更
description: docs配下のMarkdownを作成、編集、移動、分割するときの手段、配置、キー、検査、破壊的操作を定める規約。docsの文書へ変更を加える前に読む。
tags: [convention, documentation, iwe, okf]
---

# docs文書の作成と変更

`docs/`はiweのナレッジグラフとして管理し、新規文書はiwe MCPを通して作成する。
既存文書はファイル編集ツールで変更し、移動後はリンクを手動で直す。
変更後は警告を解消し、`task okf-check`でリンクと形式を検証する。

## 対象

この規約は、エージェントが`docs/`配下のMarkdownを新規作成、編集、移動、分割するときに適用する。
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

## 配置

文書の置き場所、`index.md`の形式、新しい領域の追加手順は[steering、docs、iweの役割分担](knowledge-architecture.md)に従う。
ADRは`docs/adr/`へ置き、[ADRの運用ルール](../adr/conventions.md)に従う。

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

## 他の規約への委譲

この文書は、文書を作成または変更する手段と配置だけを定める。
日本語の文章は[日本語技術文書の文章規範](../writing/japanese-tech-writing.md)に従う。
ADRの作成条件、採番、書式、ライフサイクルは[ADRの運用ルール](../adr/conventions.md)に従う。

## 検証

変更後は`task okf-check`を実行し、OKF形式、リンク切れ、孤立文書、steeringからdocsへの参照を確認する。
Markdownの変更では、リポジトリで設定されたMarkdown lintも実行する。
