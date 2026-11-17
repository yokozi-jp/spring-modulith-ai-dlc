---
type: Convention
title: 人が必ず読む Pull Request
description: main の ruleset が承認の数を 0 にしているため、人が merge の前に必ず読む Pull Request の条件と、それ以外の merge の手順を定める規約。Pull Request を merge する前、ゲートの変更を含む Pull Request を作るときに読む。
tags: [convention, code-review, pull-request, governance]
---

# 人が必ず読む Pull Request

`main` の ruleset は承認の数を 0 にしている。
次のどれかに当たる Pull Request は、merge の前に人が差分を読む。
当たらない Pull Request は、必須チェックが通っていれば、本文の変更概要と「CI 以外で確認したこと」を確かめて merge してよい。

## 人が必ず読む条件

- 戻しにくい変更（one-way door）を含む。データの移行、公開している API の変更、削除が該当する。
- ADR、`AGENTS.md`、`.kiro/` の steering、skill、hook を変える。
- CI と hook のゲートを変える。`.github/` の workflow、`lefthook.yml`、ruleset、`Taskfile.yml` の検査 Task が該当する。
- 依存を追加する。
- テストを弱める。テストファイルを消す、テストケースを減らす、coverage や mutation の基準を下げる、検査を skip するなどが該当する。

条件に当たるかを迷うときは、当たるものとして扱う。

## 読む人

読む人は、リポジトリの管理者である。
管理者が作者の Pull Request は、作者本人が merge の前に差分を読み直す。
承認の数が 0 なので、GitHub 上の承認は要らない。
読んだ結果は、Pull Request のコメントか merge の記録に残す。

## 検査

この条件を機械で検査する仕組みはない。
ゲートで止められる誤りを見つけたときは、レビューで指摘するだけでなく、ゲートに足せないかを考える。

判断の理由は [ADR-086](../adr/ADR-086-align-main-protection-with-solo-and-agent-operation.md) に記録する。
