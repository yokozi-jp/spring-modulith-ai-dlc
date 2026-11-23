---
type: ADR
title: 'ADR-116: フロントエンドのファイルを役割ごとに分け、役割ごとの文書とチェックリストを置く'
description: フロントエンドの src にあるファイルを、置き場所とファイル名で決まる14の役割に分け、役割ごとに docs/frontend/file-roles/ の文書とチェックリストを置く決定。ADR-050 を広げず、別の ADR にする。
tags: [adr, frontend, architecture, documentation]
---

# ADR-116: フロントエンドのファイルを役割ごとに分け、役割ごとの文書とチェックリストを置く

## Status

Proposed

## Date

2026-11-23

## Context

バックエンドは、[ADR-050](ADR-050-define-backend-class-roles-and-naming.md) と `docs/backend/class-roles/` で、クラスの役割ごとに文書とチェックリストを持つ。
`.kiro/steering/backend.md` は、新しいクラスを作るときに役割を選び、その文書のチェックリストで点検するよう案内している。

フロントエンドには、置き場所と依存の向き（[フロントエンドアーキテクチャ](../frontend/architecture.md)）と、領域ごとの規約はあるが、新しいファイルを作るときに役割を選んで点検する手順はない。
規約が複数の文書に分かれているため、AI エージェントが新しいファイルを作るたびに、読む文書と点検する項目が変わる。

ADR-050 は、Java のクラス、ArchUnit、Spring Modulith を前提にしている。
フロントエンドの役割は、TypeScript のファイル、Oxlint の規則、TanStack Router の route を前提にする。
[ADR-032](ADR-032-organize-frontend-by-business-feature.md) は、フロントエンドを業務機能単位で構成すると決めたが、ファイルの役割は決めていない。

## Decision

`frontend/` の `src/` にあるファイルのうち、業務のコードと共有のコードを、置き場所とファイル名で決まる次の役割に分け、役割ごとに一つの文書を `docs/frontend/file-roles/` に置く。
各文書は、backend の `class-roles/` と同じ節（定義、置き場所と命名、必須の記述、依存してよいもの、してはいけないもの、最小の例と典型的な例、対応するテスト、アンチパターン、作成時のチェックリスト）を持つ。

- **粒度**：ファイルの種類を単位にする。`features/<feature>/` の直下には画面、入力の条件、form の組み立て、module が並ぶので、ディレクトリ単位にせず、ファイル名で分ける。`lib`、`components`、`i18n` のように、ディレクトリ全体が一つの役割になる場所は、ディレクトリと役割が一対一になる。
- **対象外**：アプリの起動と組立てのファイル（`main.tsx`、`router-defaults.ts`）、CSS（`style.css`）、生成物（`routeTree.gen.ts`、Orval の生成物）には役割の文書を置かない。起動と組立ては [フロントエンドアーキテクチャ](../frontend/architecture.md#現在の構成) が、生成物は [OrvalとAPI境界](../frontend/api-client-orval.md) が定める。
- **対象**：実在するファイルがある役割だけに文書を置く。今の役割は、route、feature の画面、component、Hook、form の組み立て、入力 schema、module、アプリの共有 component、UI primitive、API 層、lib、国際化の resource、テストの道具、MSW の handler と fixture である。
- **新しい役割**：その役割のファイルを最初に作る変更と同じ変更で、文書を足す。
- **lint が止める規則**：チェックリストには、規則の内容を重ねて書かず、検査する規則の名前を書く。機械で止められない項目だけを、自分で点検する項目にする。
- **規約の正文**：振る舞いの規約（状態の置き場所、409 の扱い、cache、Effect、Hook を作る条件）の正文は、既存の `docs/frontend/` の文書にあり、役割の文書はリンクで指す。ファイルの形の規約（置き場所、命名、export の形、チェックリスト）は、役割の文書が持つ。振る舞いの規約を新しく決めるときは、既存の文書を先に直す。
- **steering**：`.kiro/steering/frontend.md` は、新しいファイルを作るときに `docs/frontend/file-roles/index.md` で役割を選ぶよう案内する。

ADR-050 は広げず、この ADR で別に決める。

## Consequences

### Positive

- 新しいファイルを作るとき、役割を選べば、読む文書と点検する項目が一つに決まる。
- lint が止める規則と、自分で点検する規則が、チェックリストで区別できる。
- backend と frontend で、役割の文書の形が揃う。

### Negative

- 役割の文書が、ファイルの形の規約を新しく持つ。振る舞いの規約は既存の文書へのリンクにとどめるが、依存してよいものの一覧は lint の表と重なるので、lint の設定を変えるときは役割の文書も見直す。
- 14文書の保守が要る。コードが変わると、引用した例が古くなる。

### Neutral

- 役割の文書は、実在するファイルから起こしたので、今は業務機能が `orders` と `tenancy` の二つだけである。業務機能が増えたら、例を見直す。
- 例の鮮度を機械で確かめる仕組みは、この ADR では決めない。

## Alternatives Considered

### 選択肢1: ADR-050 を frontend へ広げる

- **Description**：ADR-050 の対象を frontend まで広げ、一つの ADR で両方の役割を定める。
- **Pros**：役割を文書で定める方針が一箇所にまとまる。
- **Cons**：ADR-050 は ArchUnit と Java のクラスを前提にしており、Decision の大半が frontend に当てはまらない。Proposed の ADR を書き直すと、backend の判断の履歴が読みにくくなる。

### 選択肢2: ディレクトリ単位で一つの文書にする

- **Description**：`routes/`、`features/`、`components/` のようにディレクトリごとに一つの文書を置く。
- **Pros**：文書の数が少ない。
- **Cons**：`features/<feature>/` の直下に、画面、入力の条件、form の組み立てが並び、一つの文書に複数の役割の規則が混ざる。作成時のチェックリストが長くなり、点検の対象が曖昧になる。

### 選択肢3: 役割の文書を置かず、既存の領域の文書だけにする

- **Description**：今の `docs/frontend/` の文書を保ち、ファイルを作るときに、エージェントが関連する文書を探す。
- **Pros**：文書の重複がない。
- **Cons**：ファイルの役割から読む文書へたどれず、点検の漏れが起きる。

## References

- [ADR-050: バックエンドのクラスの役割と命名を定める](ADR-050-define-backend-class-roles-and-naming.md)
- [ADR-032: Frontend を業務機能単位で構成する](ADR-032-organize-frontend-by-business-feature.md)
- [ファイルの役割](../frontend/file-roles/index.md)
- yokozi-jp/spring-modulith-ai-dlc#357
