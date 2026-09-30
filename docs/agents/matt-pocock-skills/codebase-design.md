---
type: Reference
title: 深いモジュールを設計するスキルの日本語リファレンス
description: Matt Pocockのcodebase-designスキルが定める設計語彙、深いmodule、seam、test戦略、複数interface案の比較手順を参照するときに読む固定版の日本語リファレンス。
tags: [reference, agent-skills, matt-pocock]
---
# 深いモジュールの設計

この文書は実行用のSKILL.mdではなく、固定した上流版の日本語リファレンスである。
プロジェクトでの運用は[Matt Pocock スキル運用](../../matt-pocock-skills.md)を優先する。

## 概要

小さなinterfaceの背後へ多くのbehaviorを置き、明確なseamに配置し、そのinterfaceからtestできる深いmoduleを設計する。
callerにはleverage、maintainerにはlocality、全員にはtestabilityをもたらすことを目的とする。

## 使う場面

codeを新しく設計するとき、既存構造を組み替えるとき、浅いmodule群を深くするとき、または複数のinterface案を比較するときに使う。

## 入力と前提

対象module、callerが必要とするbehavior、依存関係、既存interface、testの状況が必要である。
代替interfaceを検討する場合は、projectの`GLOSSARY.md`にあるdomain languageも使う。

## 設計語彙

以下の用語を正確に使い、`component`、`service`、`API`、`signature`、`boundary`へ言い換えない。

- **module**：interfaceとimplementationを持つものを指し、function、class、package、複数tierをまたぐsliceを含む。
- **interface**：type signatureだけでなく、invariant、ordering constraint、error mode、required configuration、performance characteristicを含む、callerが正しく使うために知る必要がある全事項を指す。
- **implementation**：module内部のcodeを指す。
- **depth**：callerまたはtestが、学ぶべきinterface量あたりに実行できるbehavior量として得るleverageを指す。
- **seam**：その場所を編集せずbehaviorを変更できる位置であり、moduleのinterfaceを置く場所を指す。
- **adapter**：seamでinterfaceを満たす具体物の役割を指し、中身の種類を指さない。
- **leverage**：callerがdepthから得る能力であり、一つのimplementationが複数のcall siteとtestへ効果を返すことを指す。
- **locality**：変更、不具合、知識、検証がcaller群へ散らばらず一か所へ集まる性質を指す。

adapterとimplementationは区別する。
小さなadapterが大きなimplementationを持つ場合も、大きなadapterが小さなimplementationを持つ場合もある。
seamを論じるときはadapter、それ以外ではimplementationを使う。

## 手順

interfaceを小さくし、依存を分類し、適切なseamとtest surfaceを選ぶ。
代替interfaceが必要な場合は、三つ以上の根本的に異なる案を並行して比較する。

## 深さを評価する原則

深いmoduleは小さなinterfaceの背後に多くのimplementationを隠す。
浅いmoduleはinterfaceがimplementationとほぼ同じ複雑さを持つため避ける。

interfaceを設計するときは、method数を減らせるか、parameterを単純化できるか、内部へ隠せる複雑さを増やせるかを問う。

深さはimplementationではなくinterfaceの性質である。
深いmoduleの内部は小さく交換可能な部分から構成してよいが、それらを外部interfaceへ含めない。
moduleは外部interface上のseamに加え、implementationと内部testだけが使うinternal seamを持てる。

削除テストでは、対象moduleを消した場合を想像する。
複雑さが消えるならpass-throughであり、複数callerへ再出現するならmoduleは役割を果たしている。

interfaceをtest surfaceとする。
callerとtestは同じseamを通る。
interfaceの先までtestしたくなる場合は、moduleの形が適切でない可能性が高い。

一つのadapterしかないseamは仮想的であり、二つのadapterがあるseamは実在する。
実際にbehaviorが変わらない限りseamを導入しない。

Ousterhoutのようにimplementation行数とinterface行数の比でdepthを測らない。
その評価はimplementationの水増しを有利にするため、depthをleverageとして扱う。
interfaceをTypeScriptの`interface` keywordやclassのpublic methodだけへ限定しない。
DDDのbounded contextと意味が重なる`boundary`を使わず、seamまたはinterfaceを使う。

## testabilityを高める形

依存をmodule内で生成せず、外から受け取る。
side effectだけを起こさず、結果を返す。
methodとparameterを減らし、test数とsetupを小さくする。

一つのmoduleはcallerとtestへ一つのinterfaceを示す。
depthは、そのinterfaceに対するmoduleの性質である。
seamへmoduleのinterfaceを置き、adapterがそのinterfaceを満たす。
depthからcallerのleverageとmaintainerのlocalityが生まれる。

## 依存に応じた深め方

浅いmodule群を深める前に、依存を次の四種類へ分類する。
分類によって、新しいseamを越えるtest方法を決める。

### process内の依存

純粋計算、memory state、I/Oなしの依存である。
常に深められるため、moduleを統合し、新しいinterfaceから直接testする。
adapterは不要である。

### localで代替可能な依存

Postgresに対するPGLiteやin-memory filesystemのように、local test stand-inを持つ依存である。
stand-inが存在する場合に深められ、test suite内でstand-inを動かしてtestする。
seamはinternalとし、moduleのexternal interfaceへportを出さない。

### 所有するremote依存

自分たちが所有するmicroserviceまたはinternal interfaceなど、network越しの依存である。
seamへportを定義し、深いmoduleがlogicを所有し、transportをadapterとして注入する。
productionではHTTP、gRPC、queueのadapterを使い、testではin-memory adapterを使う。

### 制御できない外部依存

StripeやTwilioなど、制御できないthird-party依存である。
深いmoduleへ外部依存のportを注入し、testではmock adapterを渡す。

## seamとtestの規律

通常はproduction用とtest用の二つのadapterが正当化できる場合だけportを導入する。
testがinternal seamを使うという理由だけで、そのseamをexternal interfaceへ公開しない。

新しい深いmoduleのinterfaceにtestを置いたら、浅いmoduleの古いunit testを削除する。
新旧testを重ねず、置き換える。
observable outcomeをinterface越しにassertし、internal stateをassertしない。
implementationをrefactorするたびに変わるtestはinterfaceの先をtestしているため、その形を見直す。

## 複数interface案の比較手順

選んだ深め方に対して代替interfaceを検討するときは、次の手順を使う。
最初の案が最善とは限らないため、根本的に異なる案を並行して作る。

### 問題空間の提示

新しいinterfaceが満たすconstraint、利用する依存とその分類、constraintを具体化する粗いcode sketchを利用者へ示す。
sketchは提案ではなく、constraintを具体化する例にとどめる。
提示後は利用者の応答を待たず、sub-agentの起動へ進む。

### 三つ以上の並行設計

三つ以上のsub-agentを並行して起動し、それぞれに根本的に異なるinterfaceを作らせる。
各agentへfile path、coupling、依存分類、seamの背後へ置く内容を含む独立したtechnical briefを渡す。
codebase-designの語彙と`GLOSSARY.md`のdomain languageをbriefへ含める。

少なくとも次の異なるconstraintを割り当てる。

1. entry pointを最大1件から3件へ絞り、entry pointあたりのleverageを最大化する。
2. 多くのuse caseと拡張を支えるflexibilityを最大化する。
3. 最も多いcallerのdefault caseを簡単にする。
4. 該当する場合は、seam越しの依存をportとadapterで扱う。

各sub-agentは、type、method、parameter、invariant、ordering、error modeを含むinterface、usage example、seamの背後へ隠す内容、依存とadapterの戦略、leverageが厚い箇所と薄い箇所のtrade-offを出力する。

### 比較と推奨

案を一つずつ順番に提示する。
depth、locality、seam placementによって文章で比較する。
最も強い案と理由を自分で推奨し、組み合わせが有効ならhybridを提案する。
選択肢だけを並べて終えてはならない。

## 成果物

通常の成果物は、共通語彙に基づくmodule、interface、seam、adapterの設計と、そのinterfaceを通るtest構成である。
深める場合は、依存分類、adapter戦略、古い浅いtestを置き換える新しいtestも含む。
複数案を比較する場合は、三つ以上のinterface、usage、隠蔽内容、依存戦略、trade-off、比較、推奨案またはhybridを含む。
原文には、通常の設計作業全体に対する独立した完了チェックリストはない。

## 関連スキル

`improve-codebase-architecture`は、この語彙と設計原則を使用する。

## 注意点

用語の一貫性を崩さない。
一つのadapterのためだけにseamを追加せず、test都合でinternal seamを公開しない。
新しいtestを古い浅いtestへ追加するのではなく、interface上のtestで置き換える。

## 翻訳元

リポジトリは`mattpocock/skills`である。
固定コミットは`d81f3a183412e71a5b1e84ca21bc1a35eea03a60`である。
原文のskillPathは`skills/engineering/codebase-design/SKILL.md`である。
同梱資料は`skills/engineering/codebase-design/DEEPENING.md`と`skills/engineering/codebase-design/DESIGN-IT-TWICE.md`である。
