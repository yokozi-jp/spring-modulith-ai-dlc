---
type: Convention
title: ドメイン文書
description: エージェントがGlossaryとADRを読む方法とsingle-context構成を定める。コードベースを調査し、用語または設計判断を扱うときに読む。
tags: [convention, agent-skills, glossary, adr]
---

# ドメイン文書

ドメイン文書はsingle-context構成で管理する。
調査前に、存在するGlossaryと対象領域に関係するADRを読む。

## 調査前に読む文書

- リポジトリルートの`GLOSSARY.md`
- `docs/adr/index.md`と対象領域に関係するADR

`GLOSSARY.md`が存在しない場合は、その不在を問題として報告せずに作業を続ける。
プロジェクト固有の用語が確定するまで、空のGlossaryを作らない。

## 文書の配置

```text
/
├── GLOSSARY.md       # 必要になった時点で作成する
└── docs/
    └── adr/
```

## 用語

成果物でドメイン概念を使うときは、`GLOSSARY.md`で定義された語を使う。
必要な概念が未定義の場合は、既存の語で表現できないか確認し、実際に不足していれば`domain-modeling`で扱う候補として記録する。

## ADRとの競合

成果物が既存のADRと矛盾する場合は、対象ADRと矛盾の内容を明示する。
既存の判断を暗黙に上書きしない。
