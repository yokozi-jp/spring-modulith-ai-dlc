---
inclusion: auto
name: code-review
description: 変更、差分、PR の内容をレビューして問題（セキュリティ、アーキテクチャ、規約違反、仕様との不一致など）を探すときに使う。レビュー観点と照らす規約の入口を示す。
---

# コードレビューの入口

詳細は `docs/code-review/index.md` から必要な文書だけ読む。

## 行動指針

- 観点ごとに照らす規約は `docs/code-review/review-viewpoints.md` から辿り、差分が触れる観点の文書だけ読む。
- 人とツールの振り分けは `docs/code-review/review-scope.md` に従う。
- 機械的に検査できる指摘は、レビューコメントではなく Lint や CI に寄せる。検査の一覧は `docs/tooling/lint-and-test.md` にある。
