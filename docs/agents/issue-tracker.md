---
type: Reference
title: Issue tracker
description: GitHub IssuesをIssue trackerとして操作する方法を定める。Issueの作成、取得、更新、またはスキルの成果を公開するときに読む。
tags: [reference, agent-skills, github, issue-tracker]
---

# Issue tracker

Issueと仕様はGitHub Issuesで管理する。
リポジトリ内で`gh` CLIを実行し、remoteから対象リポジトリを解決する。

## 基本操作

- **作成**：`gh issue create --title "..." --body "..."`
- **取得**：`gh issue view <number> --comments`
- **一覧**：`gh issue list --state open --json number,title,body,labels,comments`
- **コメント**：`gh issue comment <number> --body "..."`
- **ラベル追加**：`gh issue edit <number> --add-label "..."`
- **ラベル削除**：`gh issue edit <number> --remove-label "..."`
- **終了**：`gh issue close <number> --comment "..."`

複数行の本文にはheredocを使う。
Issue一覧を処理するときは、必要な`--label`と`--state`を指定し、`jq`で対象フィールドを抽出する。

## Pull requestの扱い

**Pull requestをtriageの依頼面として扱わない。**

IssueとPull requestは番号空間を共有する。
番号の種類が不明な場合は`gh pr view <number>`を試し、該当しなければ`gh issue view <number>`を実行する。

## スキルからの操作

スキルがIssue trackerへの公開を指示した場合は、GitHub Issueを作成する。
スキルが関連チケットの取得を指示した場合は、`gh issue view <number> --comments`を実行する。

## Wayfinderの操作

Mapは`wayfinder:map`ラベルを付けた一つのIssueとして作成する。
子チケットはGitHubのsub-issueとしてMapへ関連付ける。
Sub-issueを使えない場合は、Map本文のtask listと子チケット本文先頭の`Part of #<map>`で関連を記録する。

子チケットには`wayfinder:<type>`ラベルを付ける。
`<type>`には`research`、`prototype`、`grilling`、`task`のいずれかを使う。

依存関係にはGitHubのissue dependenciesを使う。
利用できない場合は、子チケット本文先頭の`Blocked by: #<n>, #<n>`で記録する。

着手可能なチケットは、未完了のblockerとassigneeがないMap配下のopen IssueからMapの記載順で選ぶ。
着手時は`gh issue edit <number> --add-assignee @me`で自分を割り当てる。
解決時は回答をコメントし、Issueを閉じ、Mapの決定記録へ結果の参照を追加する。
