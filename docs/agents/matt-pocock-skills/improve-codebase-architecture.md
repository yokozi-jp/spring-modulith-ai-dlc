---
type: Reference
title: コードベースのアーキテクチャ改善スキルの日本語リファレンス
description: Matt Pocockのimprove-codebase-architectureスキルが定める改善候補の探索、HTMLレポート、候補選択後の検討手順を参照するときに読む固定版の日本語リファレンス。
tags: [reference, agent-skills, matt-pocock]
---
# コードベースのアーキテクチャ改善

この文書は実行用のSKILL.mdではなく、固定した上流版の日本語リファレンスである。
プロジェクトでの運用は[Matt Pocock スキル運用](../../matt-pocock-skills.md)を優先する。

## 概要

architecture上の摩擦を見つけ、浅いmoduleを深くする候補を可視化し、選ばれた候補を利用者と検討するスキルである。
testabilityとAI navigabilityの向上を目的とする。

## 使う場面

codebaseを走査してdeepening候補を探し、視覚的なHTML reportで比較した後、一つの候補を詳しく検討するときに使う。

## 入力と前提

最初に`codebase-design`スキルを読み、module、interface、depth、seam、adapter、leverage、localityというarchitecture語彙と原則を使う。
`component`、`service`、`API`、`boundary`へ言い換えてはならない。
存在する場合は`GLOSSARY.md`からdomain languageを取得し、対象領域の`docs/adr/`から再検討してはならない既存判断を確認する。

## 手順

### 走査範囲の決定

将来変更されるmoduleを深めるほど効果があるため、走査前に対象範囲を決め、最近変更された領域を重く見る。
利用者がmodule、subsystem、pain pointなどの方向を指定した場合は、その範囲を採用し、変更履歴からの推定を省略する。

指定がない場合は、`git log --oneline`で十分な長さのcommit historyを遡る。
何度も変更されるfileと領域をhot spotとして優先する。
変更が分散して明確なhot spotがない場合は、走査範囲を広げる。

codebaseを調べる前に`GLOSSARY.md`と対象領域のADRを読む。

### 摩擦の探索

sub-agentを起動してcodebaseを歩かせる。
固定したheuristicへ機械的に当てはめず、次の摩擦が生じる箇所を調べる。

- 一つのconceptを理解するために、多数の小さなmoduleを往復する箇所を調べる。
- interfaceがimplementationとほぼ同じ複雑さを持つ浅いmoduleを調べる。
- testabilityのために純粋関数を抽出した一方、実際の不具合がcaller間の接続に残り、localityがない箇所を調べる。
- 密結合のmoduleがseamを越えて詳細を漏らす箇所を調べる。
- testされていない箇所、または現在のinterfaceからtestしにくい箇所を調べる。

浅いと疑う対象へ削除テストを適用する。
削除したとき複雑さが消えるのではなく、一か所へ集まる候補を残す。

### HTML reportの作成

自己完結したHTMLをrepositoryではなくOSのtemporary directoryへ書く。
`$TMPDIR`を使い、未設定ならLinuxとmacOSでは`/tmp`、Windowsでは`%TEMP%`を使う。
file名は`architecture-review-<timestamp>.html`とし、毎回新しいfileを作る。

Linuxでは`xdg-open <path>`、macOSでは`open <path>`、Windowsでは`start <path>`で開く。
利用者へ絶対pathを伝える。

layoutとstyleにはTailwind CDNを使う。
graph、flow、sequenceにはMermaid CDNを使い、mass diagram、cross-section、collapse animationなど編集的な表現には手作りのCSSまたはSVGを使う。
Mermaidだけへ依存せず、候補ごとにbeforeとafterの視覚化を必ず置く。

headerにはrepository名、日付、solid boxはmodule、dashed lineはseam、red arrowはleakage、太いdark boxはdeep moduleという短いlegendを置く。
導入段落は置かず、すぐ候補を示す。

各候補を一つのcardとして、次の情報を含める。

- 対象fileとmoduleを示す。
- 現在のarchitectureが生む摩擦を一文で示す。
- 変更内容をplain languageの一文で示す。
- locality、leverage、test改善としてbenefitを示す。
- 浅さとdeepeningを表すbeforeとafterの図を横並びで示す。
- `Strong`、`Worth exploring`、`Speculative`のいずれかをrecommendation strengthのbadgeとして示す。
- `in-process`、`local-substitutable`、`ports & adapters`、`mock`のdependency categoryをbadgeとして示す。

benefitの箇条書きは各6語以下、最大6件とする。
図の理解に説明段落が必要な場合は、説明を増やさず図を描き直す。
ADRと衝突する候補は、実際の摩擦がADRの再検討に値する場合だけ載せ、該当ADRと再検討理由をwarningとして明示する。
ADRが禁じる理論上のrefactorをすべて列挙してはならない。

最後に、最初に取り組む候補と理由を一文で示す`Top recommendation`を置き、その候補cardへのanchor linkを付ける。
report内の文章では`GLOSSARY.md`のdomain languageと`codebase-design`のarchitecture語彙を使う。
`cleaner code`や`easier to maintain`のような語へ逃げず、locality、leverage、interface、implementationによって効果を表す。

reportは余白を広くした簡潔なeditorial styleとし、色は一つのaccent、leakage用のred、warning用のamberへ絞る。
diagramは約320pxの高さにし、beforeとafterをscrollなしで並べる。
scriptはTailwind CDNとMermaid ESM importだけとし、それ以外はstaticにする。

この時点ではinterfaceを提案してはならない。
fileを作成して開いた後、どの候補を検討するか利用者へ尋ねる。

### 選択された候補の検討

利用者が候補を選んだら、`grilling`スキルを呼び出す。
constraint、dependency、深いmoduleの形、seamの背後へ置く内容、残すtestをdecision treeに沿って検討する。

判断が固まる過程で、`domain-modeling`スキルを使ってdomain modelを更新する。
深いmoduleを`GLOSSARY.md`にないconceptで命名する場合は、そのtermを追加し、fileがなければ必要になった時点で作る。
曖昧なtermが明確になった場合は、その場で`GLOSSARY.md`を更新する。

利用者が将来も同じ候補を却下すべき理由で候補を退けた場合は、将来のarchitecture reviewで再提案しないようADRへの記録を提案する。
単に今は見合わないという一時的理由や、自明な理由にはADRを提案しない。

深いmoduleの代替interfaceを検討する場合は、`codebase-design`スキルのDesign It Twice patternを使う。

## 成果物

最初の成果物は、OSのtemporary directoryに置いた単一のHTML architecture reviewと、その絶対pathである。
reportには候補card、各候補のbeforeとafterの図、recommendation strength、dependency category、必要なADR warning、`Top recommendation`を含める。

候補選択後は、grillingで固めたconstraint、dependency、moduleの形、seamの内容、残すtestが成果になる。
必要に応じて、更新した`GLOSSARY.md`、ADRの提案、複数のinterface案も生じる。
原文には全工程を閉じる独立した完了チェックリストはない。

## 関連スキル

開始時に`codebase-design`を必ず使う。
候補選択後に`grilling`を使う。
domain languageまたは永続的な判断を更新するときに`domain-modeling`を使う。
代替interfaceを比較するときに`codebase-design`のDesign It Twiceを使う。

## 注意点

対象を決めずcodebase全体を無差別に走査しない。
HTML reportをrepositoryへ置かない。
利用者が候補を選ぶ前にinterfaceを提案しない。
図を長い説明で補わず、伝わらない場合は図を修正する。
用語が固まったら`GLOSSARY.md`へ反映し、将来にも必要な却下理由だけをADR候補にする。

## 翻訳元

リポジトリは`mattpocock/skills`である。
固定コミットは`d81f3a183412e71a5b1e84ca21bc1a35eea03a60`である。
原文のskillPathは`skills/engineering/improve-codebase-architecture/SKILL.md`である。
同梱資料は`skills/engineering/improve-codebase-architecture/HTML-REPORT.md`である。
