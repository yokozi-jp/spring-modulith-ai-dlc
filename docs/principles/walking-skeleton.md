---
type: Convention
title: Walking Skeleton による経路の先行確立
description: 業務機能を作り込む前に、全構成要素を貫く最小の処理経路と自動デプロイのパイプラインを先に通す方針と、新しい構成要素を加えるときの適用を定める規約。新しいシステムを立ち上げるとき、外部 API やメッセージブローカーなど新しい構成要素を導入するときに読む。
tags: [convention, principles, ci, delivery, future-arch-guidelines]
---

# Walking Skeleton による経路の先行確立

業務ロジックを作り込む前に、全構成要素を貫く最小の処理経路を通し、コミットから検証環境までの自動ビルド、テスト、デプロイを開通させる。
新しい構成要素（外部 API、メッセージブローカー、新しいデプロイ先など）を加えるときも、業務機能より先に最小の経路を通して技術的なリスクを確かめる。
骨格を通したあとは、常にデプロイできる状態を保ったまま業務機能を少しずつ加える。

## 用語

- **Walking Skeleton**：フロントエンド、バックエンド、DB、外部 API を貫いて動く最小の機能と、それをデプロイするパイプラインの組。

## ルール

1. 最小の経路で、構成要素間の通信、認証、設定の注入、デプロイが動くことを確かめる。業務ロジックは最小限にする。
2. 経路はローカルで動かすだけで終えず、CI と CD で検証環境まで自動でデプロイする。
3. 骨格を通したあとは、`main` を常にデプロイできる状態に保ち、業務機能を小さな変更で加える（[ADR-017](../adr/ADR-017-adopt-trunk-based-repository-governance.md)）。

## 出典

- フューチャー株式会社「アーキテクチャ原則ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forPrinciple/principle_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
