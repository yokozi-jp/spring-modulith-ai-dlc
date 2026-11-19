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
- **取得**：`gh issue view <number> --json number,title,body,labels,comments`
- **一覧**：`gh issue list --state open --json number,title,body,labels,comments`
- **コメント**：`gh issue comment <number> --body "..."`
- **ラベル追加**：`gh issue edit <number> --add-label "..."`
- **ラベル削除**：`gh issue edit <number> --remove-label "..."`
- **終了**：`gh issue comment <number> --body "..."`の後に`gh issue close <number>`
- **sub-issue**：`gh issue create --parent <parent>`、または`gh issue edit <parent> --add-sub-issue <child>`
- **依存**：`gh issue create --blocked-by <n>,<n>`、または`gh issue edit <child> --add-blocked-by <blocker>`
- **未完了のblockerの数**：`gh api repos/<owner>/<repo>/issues/<n> --jq .issue_dependencies_summary.blocked_by`

複数行の本文にはheredocを使う。
閉じ済みのIssueへの`gh issue close --comment`は、コメントを捨てても終了コード0で終わるため、終了ではコメントを先に投稿する。
sub-issueの操作と依存の操作は、`gh` 2.94以上で使える。
依存を張ったときは、本文に`Blocked by`を書かない。
Issueの本文には、実在する番号だけで相互参照を書く。
仮の`#<n>`は無関係なIssueへ自動でリンクされる。
Issue一覧を処理するときは、必要な`--label`と`--state`を指定し、`jq`で対象フィールドを抽出する。

## Pull requestの扱い

**Pull requestをtriageの依頼面として扱わない。**

IssueとPull requestは番号空間を共有する。
番号の種類が不明な場合は`gh pr view <number>`を試し、該当しなければ`gh issue view <number>`を実行する。

## スキルからの操作

スキルがIssue trackerへの公開を指示した場合は、GitHub Issueを作成する。
スキルが関連チケットの取得を指示した場合は、基本操作の取得と同じ操作を行う。

## Wayfinderの操作

Mapは`wayfinder:map`ラベルを付けた一つのIssueとして作成する。
子チケットはGitHubのsub-issueとしてMapへ関連付ける。
基本操作のsub-issueの操作を使う。
Sub-issueを使えない場合は、Map本文のtask listと子チケット本文先頭の`Part of #<map>`で関連を記録する。

子チケットには`wayfinder:<type>`ラベルを付ける。
`<type>`には`research`、`prototype`、`grilling`、`task`のいずれかを使う。
MapとチケットにはWayfinderのラベルだけを付け、`ready-for-agent`などのtriageラベルは付けない。
Mapとチケットは実装作業ではなく判断だからである。

依存関係にはGitHubのissue dependenciesを使う。
基本操作の依存の操作で張る。
利用できない場合は、子チケット本文先頭の`Blocked by: #<n>, #<n>`で記録する。
未完了のblockerの数は、基本操作の操作で数える。

着手可能なチケットは、未完了のblockerとassigneeがないMap配下のopen IssueからMapの記載順で選ぶ。
着手時は`gh issue edit <number> --add-assignee @me`で自分を割り当てる。
解決時は回答をコメントし、Issueを閉じ、Mapの決定記録へ結果の参照を追加する。
