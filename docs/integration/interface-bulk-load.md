---
type: Convention
title: 大量データの取り込み性能
description: 大きな連携ファイルを PostgreSQL へ取り込む方法、取り込み後の統計情報の更新、性能の見積もりと確認の時期を定める規約。大量件数のファイルの取り込みを実装するとき、バッチウィンドウに収まるかを見積もるときに読む。
tags: [convention, integration, interface, postgresql, performance, future-arch-guidelines]
---

# 大量データの取り込み性能

大きくなりうる連携ファイルは、最初から PostgreSQL の `COPY` で取り込むように設計する。
`TRUNCATE` と `COPY` で洗い替えた後は `ANALYZE` を実行する。
取り込みの性能は、処理方式を変えられるプロジェクトの早い時期に確かめる。

## 取り込みの方法

- 大きくなりうるファイルの取り込みは、アプリケーションからのバッチ INSERT ではなく、最初から `COPY` で設計する。
- `TRUNCATE` と `COPY` で洗い替えた後は、`ANALYZE` で統計情報を更新する。
- 取り込みの速度が足りない場合は、取り込みの間だけインデックスを外し、完了後に作り直すことを検討する。
- バイナリ形式の `COPY` は、性能要件が厳しい取り込みだけに使う。
  ファイルが特定の DB に依存し、可読性と他システムでの再利用性を失うため。

## 見積もりと確認

- 見積もりの目安として、次の値を使う。
  列数、レコード長、サーバーの性能、インデックスで大きく変わるため、実測で確かめる。
  - アプリケーションからのバッチ INSERT は、1000件あたり0.5秒から1秒程度かかる。
  - テキスト形式の `COPY` は、1レコード1KBで100万行あたり30秒程度かかる。
- 性能の検証は、処理方式を変えられるプロジェクトの早い時期に行う。

## 出典

- フューチャー株式会社「I/F設計ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forIF/if_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
