---
inclusion: fileMatch
fileMatchPattern: ["Taskfile.yml", "lefthook.yml", ".github/workflows/**"]
name: tooling
description: Taskfile.yml、lefthook.yml、GitHub Actions のワークフローを新規作成または編集するときに、どの docs の規約とガイドを読むかを示す。
---

# 開発ツールを扱うとき

開発ツールの規約とガイドの正文は `docs/tooling/index.md` から読む。

## 行動指針

- Taskfile、Lefthook、CI を変更する前に、該当する文書を読む。
- CI と Lefthook は処理を複製せず、Taskfile の同じタスクを呼ぶ。
- 公開タスクを追加、削除、改名、または挙動を変えたら、README と `docs/tooling/index.md` 配下の関連文書を同じ変更で更新する。
- 変更後は `task --list`、対象タスク、`task lint-md` を実行する。

## どの docs を読むか

- Taskfile.yml のタスクを書く、または直すとき：`docs/tooling/taskfile.md`
- 日常の開発手順とタスクの使い方を確認するとき：`docs/tooling/dev-workflow.md`
- Lint、スキャン、テストと、フックや CI での実行の対応を確認するとき：`docs/tooling/lint-and-test.md`
