---
inclusion: fileMatch
fileMatchPattern: [".github/CODEOWNERS", ".github/workflows/release-please.yml", "release-please-config.json", ".release-please-manifest.json", "version.txt", "CHANGELOG.md"]
name: repository
description: main のブランチ保護、必須チェック、CODEOWNERS、release-please の設定やリリース手順を確認、変更するときに使う。リポジトリ運用の規約の入口を示す。
---

# リポジトリ運用の入口

詳細は `docs/repository/index.md` から必要な文書だけ読む。

## 行動指針

- 必須チェックの名前を変えるワークフローの変更では、`docs/repository/branch-protection.md` も同じ変更で更新する。
- `CHANGELOG.md` と `version.txt` は release-please が更新するため、手で編集しない。
