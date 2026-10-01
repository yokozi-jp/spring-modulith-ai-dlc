---
type: Reference
title: Grill Me の対話開始リファレンス
description: 計画または設計を厳しく検討する対話を開始するときの呼び出し方法を参照するときに読む。
tags: [reference, agent-skills, matt-pocock]
---
# Grill Me の対話開始リファレンス

この文書は実行用の `SKILL.md` ではなく、固定した上流版の日本語リファレンスである。
プロジェクトでの運用は [Matt Pocock スキル運用](workflow.md) を優先する。

## 概要

`grill-me` は、計画または設計を磨くために容赦なく質問する対話の入口である。
実際の対話には `grilling` スキルを使う。

## 使う場面

計画または設計を厳しく問い直して明確にするときに使う。
原文には、これ以外の発火条件は記載されていない。

## 入力または前提

検討する計画または設計が入力となる。
入力形式や事前準備は、原文に記載されていない。

## 手順

Skill ツールを `grilling` という名前で一回呼び出す。
その後の対話手順は `grilling` スキルが定める。

## 成果物

この wrapper が作るファイルや固定形式の成果物は、原文に記載されていない。

## 関連スキル

`grilling` が、容赦ない対話の手順を提供する。

## 注意点

`grill-me` 自体はモデルから自動起動しない設定である。
原文は `grilling` の呼び出しだけを定め、対話内容を重複して定めていない。

## 翻訳元

取得元は `mattpocock/skills` である。
コミットは `d81f3a183412e71a5b1e84ca21bc1a35eea03a60` である。
原文の `skillPath` は `skills/productivity/grill-me/SKILL.md` である。
