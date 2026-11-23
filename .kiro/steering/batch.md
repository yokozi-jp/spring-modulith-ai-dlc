---
inclusion: fileMatch
fileMatchPattern: ["backend/src/**/batch/**", "docs/batch/**"]
name: batch
description: 業務のバッチ（Spring Batch の Job、Step、ItemReader、ItemWriter、Tasklet）を作る、または変えるときに使う。ジョブの入口、追いつき、冪等性と再開、オンラインとの排他、テストの docs の入口を示す。
---

# バッチの docs への案内

詳細は `docs/batch/index.md` から必要な文書だけ読み、クラスを作るときは `docs/backend/class-roles/index.md` の役割に従う。

## 行動指針

- 業務のバッチを作る前に、`docs/adr/ADR-115-build-business-batches-with-spring-batch.md` の決定を確かめる。
- ItemWriter と Tasklet から呼ぶ CommandHandler を冪等にし、再開で同じ行が渡っても結果が変わらないことをテストで確かめる。
