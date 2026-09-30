---
type: Reference
title: 一次資料を使う調査スキルの日本語リファレンス
description: Matt Pocockのresearchスキルが定める調査の委譲、一次資料の選択、出典付きMarkdownの保存方法を参照するときに読む固定版の日本語リファレンス。
tags: [reference, agent-skills, matt-pocock]
---
# 一次資料を使う調査

この文書は実行用のSKILL.mdではなく、固定した上流版の日本語リファレンスである。
プロジェクトでの運用は[Matt Pocock スキル運用](../../matt-pocock-skills.md)を優先する。

## 概要

調査をbackground agentへ委譲し、高い信頼性を持つ一次資料に基づく結果を一つのMarkdownへ残すスキルである。
調査中も呼び出し元の作業を続けられるよう、background agentを使う。

## 使う場面

利用者が話題の調査、documentationやAPIの事実確認、または資料を読む作業のbackground agentへの委譲を求めたときに使う。

## 入力と前提

調べる問いが必要である。
保存先を決める前に、リポジトリが調査noteを置く既存規約を確認する。

## 手順

1. 調査を担当するbackground agentを起動する。
2. 公式documentation、source code、specification、first-party APIなどの一次資料を使って問いを調査させる。
3. 二次資料の説明で止めず、各主張をその情報を所有する資料まで遡らせる。
4. 各主張の出典を示し、調査結果を一つのMarkdownへまとめさせる。
5. リポジトリの既存規約と同じ場所へ保存させる。
6. 既存規約がない場合は妥当な場所へ保存し、その場所を報告させる。

## 成果物

成果物は、各主張に出典を付けた一つのMarkdownファイルである。
明示的な完了チェックリストは原文にない。

## 関連スキル

原文は、ほかの名前付きスキルを明示的には呼び出さない。

## 注意点

二次資料を最終的な根拠としてはならない。
原文は、一次資料の内容にない情報の追加や、複数ファイルへの分割を指示していない。

## 翻訳元

リポジトリは`mattpocock/skills`である。
固定コミットは`d81f3a183412e71a5b1e84ca21bc1a35eea03a60`である。
原文のskillPathは`skills/engineering/research/SKILL.md`である。
