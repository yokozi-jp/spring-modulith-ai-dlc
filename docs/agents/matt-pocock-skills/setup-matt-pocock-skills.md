---
type: Reference
title: Matt Pocock由来のエンジニアリングスキルを初期設定する手順
description: Issue管理、triageラベル、ドメイン文書のリポジトリ別設定を作るsetup-matt-pocock-skillsスキルの調査、確認、作成手順を参照するときに読む。
tags: [reference, agent-skills, matt-pocock]
---

# Matt Pocock由来のエンジニアリングスキルを初期設定する手順

この文書は実行用の`SKILL.md`ではなく、固定した上流版の日本語リファレンスである。
プロジェクトでの運用は[Matt Pocock スキル運用](workflow.md)を優先する。

## 概要

エンジニアリングスキルが前提とするリポジトリ別設定を初期作成する。
設定対象は、Issueの配置先、五つの標準triage役割に対応するラベル文字列、`GLOSSARY.md`とADRの配置および参照規則である。
決定的なスクリプトではなく、調査結果を示し、利用者へ確認してから書く対話型スキルである。

## 使う場面

他のエンジニアリングスキルを初めて使う前に、一度実行する。
Issue管理先を切り替える場合、または設定を最初からやり直す場合は再実行する。
このスキルはモデルから自動起動せず、明示的に呼び出す。

## 入力または前提

現在のリポジトリと、利用者が実際に使うIssue管理方法を入力とする。
既存ファイルを読み、初期状態を推測してはならない。

## 調査

次を確認する。

- `git remote -v`と`.git/config`で、GitHubリポジトリか、対象リポジトリはどれかを確認する。
- ルートの`AGENTS.md`と`CLAUDE.md`の有無、および既存の`## Agent skills`節を確認する。
- ルートの`GLOSSARY.md`と`GLOSSARY-MAP.md`、および旧名の`CONTEXT.md`と`CONTEXT-MAP.md`を確認する。
旧名のファイルがあれば、`git mv`で新名へ改め、`docs/agents/domain.md`の名前も直すことを提案する。
- `docs/adr/`と`src/*/docs/adr/`を確認する。
- `docs/agents/`に以前の出力があるかを確認する。
- `.scratch/`がローカルMarkdown方式の既存慣例を示すか確認する。
- 隣接フォルダまたは利用可能なスキル一覧で`triage`の導入有無を確認する。
- `pnpm-workspace.yaml`、`package.json`の`workspaces`、独自の`src/`を持つ`packages/*`から、大規模な複数パッケージ構成かを確認する。

最後の兆候がなければ単一コンテキストとする。
これはほとんどのリポジトリに当てはまる。

## 利用者への確認

存在するものと不足しているものを要約する。
各項目を順番に、一項目につき一つの回答を得てから次へ進む。
推奨回答を先に示し、一語で受諾できるようにする。
選択が実際に分岐するときだけ一行の説明を付ける。
調査で決着済みの項目は質問しない。
`triage`がなければラベルの項目を省き、複数パッケージ構成でなければドメイン文書の項目を省く。

### Issue管理先

Issue管理先は`to-tickets`、`triage`、`to-spec`が読み書きする場所であると説明する。
GitHubのremoteがあればGitHubを推奨する。
GitLabまたはセルフホストGitLabのremoteがあればGitLabを推奨する。
それ以外の場合、または利用者が望む場合は、GitHub、GitLab、`.scratch/<feature>/`のローカルMarkdown、その他の管理先を提示する。
その他を選ぶ場合は、利用者に作業方法を一段落で説明してもらい、その文章を自由形式で記録する。
選択結果は`docs/agents/issue-tracker.md`へ記録する。
GitHubまたはGitLabを選んだのに、そのホストを指す`git remote`がなければ、リポジトリのURLを利用者から得て、必要なら`git init`をしてから`git remote add origin <url>`で設定する。
`gh repo view`または`glab repo view`が、作業ディレクトリの中でプロジェクトを表示すれば完了とする。
表示できなければ、足りないものを利用者へ伝えて止まる。
この設定が済むまで先へ進まない。
GitHubとGitLabのテンプレートでは、外部PRまたはMRを依頼の入口として扱う設定を既定で無効にする。
この設定を話題にしてはならず、必要な利用者が後でファイルを変更できる状態にする。

### Triageラベル

`triage`が導入されている場合だけ、「既定のtriageラベルを維持するか」を一問だけ尋ね、推奨回答を「はい」とする。
既定値は役割名と同じ`needs-triage`、`needs-info`、`ready-for-agent`、`ready-for-human`、`wontfix`である。
「はい」ならそのまま書く。
「いいえ」の場合だけ、既存ラベルと重複しないよう上書き値を収集する。

### ドメイン文書

通常は、ルートの一つの`GLOSSARY.md`と`docs/adr/`からなる単一コンテキストを、質問せずに書く。
複数パッケージ構成の兆候が見つかった場合だけ、ルートの`GLOSSARY-MAP.md`から各コンテキストの`GLOSSARY.md`を指す複数コンテキスト構成を提示し、どちらにするか確認する。

## 下書きの確認

書き込む前に、選択した`CLAUDE.md`または`AGENTS.md`へ加える`## Agent skills`ブロックの下書きを示す。
`docs/agents/issue-tracker.md`と`docs/agents/domain.md`の全文も示す。
`triage`が導入されている場合だけ、`docs/agents/triage-labels.md`も示す。
利用者が書き込み前に編集できるようにする。

## 書き込み

`CLAUDE.md`があれば、それを編集する。
なければ`AGENTS.md`を編集する。
どちらもなければ、どちらを作るか利用者へ確認し、勝手に選んではならない。
`CLAUDE.md`があるときに`AGENTS.md`を作成してはならず、その逆も同じである。
既存の`## Agent skills`ブロックがあれば、その場で更新し、重複して追加してはならない。
周囲にある利用者の編集を上書きしてはならない。

`## Agent skills`ブロックには、Issue管理先、triageラベル、ドメイン文書の要約と対応する`docs/agents/*.md`への参照を置く。
`triage`がない場合は、triageラベルの小節と`docs/agents/triage-labels.md`を省く。
GitHubまたはGitLabでラベルの項目を実行したときは、設定したラベルのうちIssue管理先にないものを`gh label create`または`glab label create`で作る。

同梱テンプレートを出発点として、次を書く。

- GitHubでは`issue-tracker-github.md`を使い、全操作に`gh`を使う。
- GitLabでは`issue-tracker-gitlab.md`を使い、全操作に`glab`を使う。
- ローカルMarkdownでは`issue-tracker-local.md`を使い、`.scratch/<feature>/spec.md`と連番の個別Issueファイルを使う。
- `triage`がある場合は`triage-labels.md`を使い、標準役割から実際のラベルへの対応を書く。
- `domain.md`を使い、作業前に対象用語集と関係するADRを読む規則、単一または複数コンテキストの配置を書く。

その他のIssue管理先では、利用者の説明から`docs/agents/issue-tracker.md`を新規に書く。

## 同梱テンプレートの規則

GitHubではIssueと仕様をGitHub Issuesへ置き、作成、参照、一覧、コメント、ラベル操作、終了に`gh issue`を使う。
参照は`gh issue view <番号> --json number,title,body,labels,comments`で行う。
sub-issueは`gh issue create --parent`または`gh issue edit <親> --add-sub-issue <子>`で作る。
blockingはGitHubのネイティブな依存（`--blocked-by`、`--add-blocked-by`）で結ぶ。
この二つのフラグは`gh` 2.94以上で使え、古い`gh`では`gh api`のPOSTで代える。
未解決のblockerの数は`gh api`の`issue_dependencies_summary.blocked_by`で数える。
終了は、`gh issue comment`で説明を投稿してから`gh issue close`を実行する。
Issueは利用者自身のリポジトリにだけ作り、利用者が所有しない限り`mattpocock/skills`には作らない。
forkでは`gh`が上流の親リポジトリを既定にすることがあるため、セッションで最初に`gh issue create`をする前に`gh repo view --json nameWithOwner`で対象を確認し、利用者のリポジトリでなければ利用者に確認する。
PRを依頼の入口にする場合は`gh pr`を使い、外部PRだけを発見対象とし、明示されたPRは作者に関係なく扱う。
PRの参照は、TTYでない出力では`--comments`がコメントだけを出すため、`gh pr view <番号>`と`gh pr view <番号> --comments`を分けて実行する。
GitHubではIssueとPRが番号空間を共有するため、裸の番号がどちらかを解決する。

GitLabではIssueと仕様をGitLab Issuesへ置き、対応する`glab issue`操作を使う。
終了時の説明は`glab issue close`の前にnoteとして投稿する。
一覧のJSON出力は`glab issue list -O json`で得る。
wayfinderの子チケットは、`Part of #<map>`を本文の先頭に置き、`glab issue create --linked-issues <地図> --link-type relates_to`で地図へ結ぶ。
MRを依頼の入口にする場合は`glab mr`を使う。
GitLabではIssueとMRの番号空間が別である。

ローカルMarkdownでは、一機能につき`.scratch/<feature-slug>/`を一つ作る。
仕様は`spec.md`、実装Issueは`issues/<NN>-<slug>.md`へ一件ずつ置き、単一の統合チケットファイルを作らない。
状態は各Issue上部の`Status:`、履歴は末尾の`## Comments`へ記録する。

各Issue管理方式は、wayfinder用のマップ、子チケット、ブロック関係、frontier、claim、解決の表現も定める。
ネイティブの子Issueと依存関係が使える場合はそれを使い、使えない場合だけ本文の規約へ代替する。
claimは作業前の最初の書き込みとし、解決では回答を記録し、チケットを閉じ、マップへ要旨とリンクを追加する。

ドメイン文書を読むとき、対象ファイルがなければ黙って続行する。
不足を指摘したり、先回りして作成を提案したりしてはならない。
`domain-modeling`が用語または判断の解決時に遅延作成する。
出力では`GLOSSARY.md`の用語を使い、避けると指定された同義語へずらしてはならない。
必要な概念が用語集になければ、プロジェクト外の語を持ち込んでいないか再検討し、実際の欠落なら`domain-modeling`向けに記録する。
既存ADRと矛盾する場合は、黙って上書きせず明示する。

## 完了時の報告

設定完了と、これらのファイルを今後読むエンジニアリングスキルを利用者へ伝える。
`docs/agents/*.md`は後で直接編集でき、Issue管理先の変更または最初からの再設定を望む場合だけ再実行が必要だと伝える。

## 成果物

選択済みの`CLAUDE.md`または`AGENTS.md`に、一つの`## Agent skills`ブロックを得る。
`docs/agents/issue-tracker.md`と`docs/agents/domain.md`を得る。
`triage`が導入されている場合だけ`docs/agents/triage-labels.md`も得る。

## 関連スキル

`to-tickets`、`triage`、`to-spec`がIssue管理設定を利用する。
`domain-modeling`が用語集とADRを必要時に作る。
`wayfinder`がIssue管理文書のWayfinding操作を利用する。

## 注意点

調査、提示、利用者の確認、書き込みの順序を崩してはならない。
調査で判明していない状態を推測してはならない。
不要なファイルや重複する設定ブロックを作ってはならない。

## 翻訳元

リポジトリは`mattpocock/skills`である。
コミットは`5863806dd4e15c63174bee4e7515a4256792ecfa`である。
原文のskillPathは`.agents/skills/setup-matt-pocock-skills/SKILL.md`である。
同梱資料の`issue-tracker-github.md`、`issue-tracker-gitlab.md`、`issue-tracker-local.md`、`triage-labels.md`、`domain.md`も参照した。
