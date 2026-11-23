---
inclusion: fileMatch
fileMatchPattern: ["backend/src/**/batch/**", "docs/batch/**"]
name: batch
description: 業務のバッチ（Spring Batch の Job、Step、ItemReader、ItemWriter、Tasklet）を作る、または変えるときに使う。ジョブの入口、追いつき、冪等性と再開、オンラインとの排他、テストの docs の入口を示す。
---

# バッチの docs への案内

詳細は `docs/batch/index.md` から必要な文書だけ読む。
Job の設定、ItemReader、ItemWriter、Tasklet を作るときは、`docs/backend/class-roles/index.md` で役割を選び、その役割の文書のチェックリストで点検する。
