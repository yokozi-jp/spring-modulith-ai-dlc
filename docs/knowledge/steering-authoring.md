---
type: Convention
title: steering の書き方
description: Kiro の steering ファイル（.kiro/steering/ 配下の .md）を作成、編集するときの配置、フロントマター、inclusion モード、粒度と命名、本文、ファイル参照、機密情報、カスタムエージェント、保守を定める規約。steering ファイルを書く、または直す前に読む。
tags: [convention, steering, kiro]
---

# steering の書き方

steering はワークスペースの `.kiro/steering/` にだけ置き、フロントマターで inclusion モードを指定する。
本文は読む docs の案内、行動指針、全タスクで守る短いルールだけにし、規約の本文と理由は docs と ADR に置く。
steering に何を書くか、行数の目安、docs との依存方向は [steering、docs、iwe の役割分担](knowledge-architecture.md) に従う。

## 配置とスコープ

- steering ファイルはプロジェクトルート直下の `.kiro/steering/` に置き、このワークスペースにだけ適用する。
- ファイル形式はマークダウン（`.md`）にする。
- グローバル steering（`~/.kiro/steering/`）は使わない。全社共通の規約も、このプロジェクトでは `.kiro/steering/` に書く。

## フロントマター

フロントマターは YAML で書き、ファイルの先頭に置いて三本のダッシュ（`---`）で囲む。
その前に空行や本文を置かない。

inclusion モードは次の4つがある。

- **always**：すべての対話で読み込む（フロントマターを省いたときの既定）。全タスクで守る短いルールと、docs への入口の案内に使う。
- **fileMatch**：`fileMatchPattern` に一致するファイルを扱うときだけ読み込む。領域ごとの steering に使う。
- **manual**：チャットで `#ファイル名` と参照したときだけ読み込む。たまにしか要らない手順に使う。
- **auto**：`description` が依頼に一致したときに読み込む。関連するときだけ載せたい重い文脈に使う。

このプロジェクトでは、対象ファイルが明確な steering は `fileMatch` にする。

```yaml
---
inclusion: fileMatch
fileMatchPattern: ["backend/**"]
name: backend
description: バックエンドのコード、テスト、ビルド設定を追加や変更するときに、読むべき docs を示す。
---
```

`fileMatchPattern` に複数のパターンを指定するときは配列で書く。

`name` と `description` は、公式には `auto` で必須である。
このプロジェクトでは、`fileMatch` と `auto` の両方に付ける。

- **name**：steering ファイルの識別子。表示と一致判定に使う。
- **description**：いつ読み込むか。何を対象に、いつ使うのかを具体的に書く。

## Kiro CLI での読み込み

公式ドキュメントの対応表と Kiro CLI 3.0 の新機能一覧は、CLI でも inclusion モードに対応すると記している。
一方で、同じ Steering のページの注記には、CLI では未対応ですべての steering を読み込むとあり、記述が食い違っている。
このワークスペースの Kiro CLI では、`fileMatch` の steering が対象ファイルを扱ったときにだけ読み込まれることを確認している。

inclusion モードは CLI でも効く前提で設計する。
ただし、すべての steering が読み込まれても支障がないよう、合計の行数を目安内に保ち、docs を `#[[file:...]]` で展開しない。

## ファイルの粒度と命名

- 1つのファイルには1つの領域だけを置く。API 設計、テスト、デプロイ手順を混ぜない。
- ファイル名は領域が分かる具体的な名前にする（`backend.md`、`database.md` など）。

## 本文の書き方

- 標準的なマークダウン記法で、自然な言葉で書く。
- 書くのは、読む docs の案内、エージェントの行動指針、全タスクで守る短いルールの要約だけにする。
- 規約の本文、決定の理由、コード例、before と after の比較は steering に書かず、docs の規約文書と ADR に書く。steering からは、その文書のパスを示す。
- 短いルールを要約として置くときも、正文は docs に置く。

避けるべき書き方は次のとおり。

- docs にしかない規約の本文を steering に書き写す、または steering にしか書かない。steering の内容は iwe の検索とリンク検査の対象にならない。
- 「なぜ」を steering で説明する。理由は ADR へリンクする。

## ファイル参照

steering から docs を指すときは、パスを文字列（`docs/backend/index.md` など）で書き、`#[[file:docs/...]]` を使わない。
docs を展開すると、その steering が読み込まれるたびに docs の全文がコンテキストに入るためである。

ソースコードや設定ファイルは、`#[[file:<相対パス>]]` で参照してよい。
リポジトリを再編したら、参照先が実在するか確認する。

## 機密情報

API キー、パスワード、機密データを steering に書かない。
steering はコードベースの一部としてリポジトリで管理される。

## カスタムエージェント

カスタムエージェント（このプロジェクトの既定エージェントを含む）を使うとき、steering ファイルは自動では読み込まれない。
エージェント設定の `resources` に明示的に加える。

```json
{
  "resources": ["file://.kiro/steering/**/*.md"]
}
```

## 保守

- アーキテクチャや開発フローを変えたら、steering の案内が docs の現状と合っているかを見直す。
- 再編後は、steering が指す docs のパスとファイル参照が実在するかを確認する。`task okf-check` が docs のパスの実在を検査する。
- steering の変更はコードの変更と同じくレビューを通す。

## 出典

- Kiro Docs, Steering: <https://kiro.dev/docs/steering/>
- Kiro Docs, New features in 3.0: <https://kiro.dev/docs/cli/v3/new-features/>
