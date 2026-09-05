---
inclusion: fileMatch
fileMatchPattern: ".kiro/steering/**/*.md"
name: steering-best-practices
description: ワークスペース Steering ファイル（.kiro/steering/ 配下の .md）を新規作成・編集するときのベストプラクティス。配置とスコープ、フロントマター（inclusion モードと fileMatchPattern・name・description）、ファイルの粒度と命名、本文の書き方、ファイル参照記法、機密情報の扱い、保守、Kiro CLI 固有の制約を定める。Kiro 公式の Steering ドキュメントに準拠する。Steering ファイルを書く・直すときに使用する。
---

# Steering の書き方

Steering ファイルを作成・編集するときは、以下に従う。
すべて Kiro 公式の Steering ドキュメントに基づく（末尾の出典を参照）。

このプロジェクトはワークスペース Steering だけを使う。
グローバル Steering（`~/.kiro/steering/`）は使わない。

## 配置とスコープ

- Steering ファイルはプロジェクトルート直下の `.kiro/steering/` に置く。
このワークスペースにだけ適用される。
- ファイル形式はマークダウン（`.md`）にする。
- グローバル Steering は使わないので、`~/.kiro/steering/` にファイルを置かない。
全社共通の規約もこのプロジェクトでは `.kiro/steering/` に書く。

## フロントマター

フロントマターは YAML で書き、ファイルの**先頭**に置く。
三本のダッシュ（`---`）で囲む。
その前に空行や本文を置いてはならない。

inclusion モードは次の四つがある。

- **always**：すべての対話に自動で読み込まれる（フロントマター省略時のデフォルト）。技術スタック、コーディング規約、セキュリティ方針など、全体に効かせる基準に使う。
- **fileMatch**：`fileMatchPattern` に一致するファイルを扱うときだけ読み込まれる。特定のファイル種別にだけ効かせる規約に使う。
- **manual**：チャットで `#ファイル名` と参照したときだけ読み込まれる。トラブルシュート手順や移行手順など、たまにしか要らない文書に使う。
- **auto**：`description` がリクエストに一致したときに自動で読み込まれる。関連するときだけ載せたい重い文脈に使う。

このプロジェクトでは、対象ファイルが明確な規約は `fileMatch` を使う。

```yaml
---
inclusion: fileMatch
fileMatchPattern: ".kiro/steering/**/*.md"
---
```

複数パターンを指定するときは配列で書く。

```yaml
---
inclusion: fileMatch
fileMatchPattern: ["**/*.ts", "**/*.tsx", "**/tsconfig.*.json"]
---
```

`fileMatch` と `auto` を使うときは、`name` と `description` を必ず付ける。

- **name**：Steering ファイルの識別子。表示と一致判定に使う。
- **description**：いつこのファイルを読み込むか。Kiro がリクエストと突き合わせる。何を対象に、いつ使うのかを具体的に書く。

## ファイルの粒度と命名

- 一つのファイルには一つの領域だけを置く。API 設計、テスト、デプロイ手順を混ぜない。
- ファイル名は内容が分かる具体的なものにする。
  - `api-rest-conventions.md`：REST API の標準
  - `testing-unit-patterns.md`：ユニットテストの方針
  - `components-form-validation.md`：フォーム部品の標準

## 本文の書き方

- 標準的なマークダウン記法で、自然な言葉で要件を書く。
- 標準の内容（何を）だけでなく、その決定の理由（なぜ）も書く。
- コード片や before/after の比較で標準を具体的に示す。
- 避けるべきアンチパターンも挙げる。

## ファイル参照

ワークスペースの実ファイルへリンクして、Steering を最新に保つ。

```markdown
#[[file:<相対パス>]]
```

例：

- API 仕様：`#[[file:api/openapi.yaml]]`
- 部品パターン：`#[[file:components/ui/button.tsx]]`
- 設定テンプレート：`#[[file:.env.example]]`

リポジトリを再編したら、ファイル参照が生きているか確認する。

## 機密情報の扱い

- API キー、パスワード、機密データを Steering ファイルに書かない。
Steering ファイルはコードベースの一部として管理される。

## 保守

- スプリント計画やアーキテクチャ変更のたびに見直す。
- 再編後はファイル参照を確認する。
- Steering の変更はコードの変更と同じ扱いにし、レビューを通す。

## Kiro CLI 固有の制約

- Kiro CLI では inclusion モードが現時点で機能しない。
`.kiro/steering/` 配下のすべての Steering ファイルが自動で読み込まれる。
`fileMatch` などを指定しても、CLI 上では常時読み込みになる。
IDE と Web では inclusion モードが効く。
- カスタムエージェント（このプロジェクトの既定エージェントを含む）を使うとき、Steering ファイルは自動では含まれない。
エージェント設定の `resources` に明示的に追加する必要がある。

```json
{
  "resources": ["file://.kiro/steering/**/*.md"]
}
```

## 出典

- Kiro Docs, Steering: <https://kiro.dev/docs/steering/>
