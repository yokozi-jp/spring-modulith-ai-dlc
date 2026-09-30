---
type: Reference
title: Grill With Docs の対話と記録リファレンス
description: 計画または設計を厳しく検討しながら ADR と用語集を作るためのスキル呼び出しを参照するときに読む。
tags: [reference, agent-skills, matt-pocock]
---
# Grill With Docs の対話と記録リファレンス

この文書は実行用の `SKILL.md` ではなく、固定した上流版の日本語リファレンスである。
プロジェクトでの運用は [Matt Pocock スキル運用](workflow.md) を優先する。

## 概要

`grill-with-docs` は、計画または設計を磨くために容赦なく質問し、進行に合わせて ADR と用語集を作る入口である。
対話とドメイン文書の処理を別の二つのスキルへ委ねる。

## 使う場面

計画または設計を厳しく検討し、その過程で ADR と用語集も作るときに使う。
原文には、これ以外の発火条件は記載されていない。

## 入力または前提

検討する計画または設計が入力となる。
入力形式や文書配置の前提は、原文に記載されていない。

## 手順

Skill ツールを二回呼び出す。
一回は `grilling` を指定する。
もう一回は `domain-modeling` を指定する。
呼び出し順序は、原文に記載されていない。

## 成果物

概要では、進行に合わせて ADR と用語集を作るとされている。
具体的な形式、配置、完了条件は、このスキルの原文に記載されていない。

## 関連スキル

`grilling` が、計画または設計を問い直す対話を担う。
`domain-modeling` が、ADR と用語集に関する処理を担う。

## 注意点

`grill-with-docs` 自体はモデルから自動起動しない設定である。
この原文は二つのスキルを必ず呼び出すことだけを定め、各スキルの手順を重複して定めていない。

## 翻訳元

取得元は `mattpocock/skills` である。
コミットは `d81f3a183412e71a5b1e84ca21bc1a35eea03a60` である。
原文の `skillPath` は `skills/engineering/grill-with-docs/SKILL.md` である。
