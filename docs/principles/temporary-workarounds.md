---
type: Convention
title: 暫定対応と運用回避の扱い
description: レビューで指摘した方針からの逸脱やテストの漏れを「後で直す」「運用で対応する」として残さないための扱いと、やむを得ず残す場合の Issue の書き方、期限を過ぎたときの判断、運用で回避する場合の手順の残し方を定める規約。レビューで暫定対応を求められたとき、指摘を後回しにするか判断するときに読む。
tags: [convention, principles, code-review, technical-debt, future-arch-guidelines]
---

# 暫定対応と運用回避の扱い

アーキテクチャ方針からの逸脱やテストの漏れを、「後で直す」という口約束でマージしない。
ツールで検出できる方針は検査に組み込み、違反するとマージできない状態にする。
やむを得ず残す場合は担当と期限を書いた Issue を起票し、運用で回避する場合は手順を docs に書く。

## 検査で暫定対応を防ぐ

- 方針のうちツールで検出できるものは、Lint や ArchUnit などの検査として Task と CI に組み込み、違反をマージできなくする。観点の振り分けは[レビューで人が確認する範囲](../code-review/review-scope.md)に従う。
- 既存の検査は[Lintとテストのリファレンス](../tooling/lint-and-test.md)で確かめる。

## 後回しにする場合

レビューの指摘を同じ Pull Request で直さない場合は、次をすべて満たす。

- マージ前に Issue を起票し、Pull Request から Issue へリンクする。
- Issue に、直す内容、担当者、期限を書く。
- 担当者の作業の割り当てを別の人が決めている場合は、その人へ Issue を必須の作業として伝える。

Issue の操作は[Issue tracker](../agents/issue-tracker.md)に従う。

## 期限を過ぎた Issue

期限を過ぎた Issue は督促を繰り返さず、次のどちらかに決める。

- 担当を替えるか、指摘した人が自ら直す。
- 直さないと決め、理由を書いて Issue を閉じる。直さない判断が [ADR の運用ルール](../adr/conventions.md)の作成基準に当たる場合は ADR を起こす。

## 運用で回避する場合

- 「運用で対応する」とする場合は、回避の手順を Runbook として docs に書き、引き継げる状態にする。
- 手順を書いて引き継ぐコストが、元の問題を直すコストと同じ程度なら、元の問題を直す。

## 出典

- フューチャー株式会社「アーキテクチャ原則ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forPrinciple/principle_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
