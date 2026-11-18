---
type: Reference
title: Kiro のフック
description: .kiro/hooks の PreToolUse フックが拒否する書き込み、守るパスの置き場所、判定の範囲、既知の限界、自己検査、フックを通らない書き換えの検出を示すリファレンス。エージェントの書き込みがフックに拒否されたとき、フックや守るパスを変えるときに読む。
tags: [reference, kiro, hooks, agent-skills]
---

# Kiro のフック

`.kiro/hooks/` の PreToolUse フックは、エージェントの書き込みを `exit 2` で拒否し、STDERR に直し方を示す。

## block-generated-writes

`.kiro/hooks/block-generated-writes.json` が書き込み系のツールの呼び出しを `.kiro/hooks/block-generated-writes.sh` に渡し、スクリプトが判定する。
対象のツールと、判定に使う入力の項目は、スクリプトの冒頭のコメントにある。

拒否するパスと、パスの置き場所は次のとおり。

- **生成物**：`frontend/apps/web/src/routeTree.gen.ts`、`frontend/packages/api/src/generated/**`、`openapi/openapi.yaml`。パスはスクリプトだけが持つ。再生成の手順は [フロントエンドのテストと検証](../frontend/testing.md)の「生成物」にある。
- **cursor-plugins の `kiro/install.sh` が入れたファイル**：`.kiro/.kiro-plugins-installed` に載るすべてのパスと、この記録自身。載るパスは実行のたびに記録から読む。入れ直す手順は [スキルの取得元と更新](skill-sources.md)にある。

判定の範囲は次のとおり。

- このリポジトリの中のパスだけを判定し、`~/.kiro` や別の checkout のパスは通す。
- パスの `//` と `/./` は詰めてから判定し、`..` は解決しない。
- STDIN がない、JSON として読めない、`jq` がない、パスの項目がない、のいずれかのときは書き込みを許可する。

rename や move のツールが他のファイルに加える編集と、shell のリダイレクトはフックを通らない。
これらの書き換えは、CI で動く `task fe-route-tree-check`、`task api-client-check`、`task be-openapi-check`、`task skills-check` が検出する。

自己検査は `bash .kiro/hooks/block-generated-writes.test.sh` で実行する。
`task skills-check` が最初にこの自己検査を実行するため、`.kiro/` を変えると Lefthook の pre-commit と CI でも動く。

## block-iwe-normalize

`iwe normalize` の通常の実行を拒否する。
拒否する理由と、意図して実行する手順は [docs文書の作成と変更](../knowledge/documentation-authoring.md)の「破壊的操作」にある。
