---
type: Reference
title: 設計上の問いに答えるプロトタイプスキルの日本語リファレンス
description: Matt Pocockのprototypeスキルが定めるロジック用とUI用の分岐、共通規則、作成手順、保存方法を参照するときに読む固定版の日本語リファレンス。
tags: [reference, agent-skills, matt-pocock]
---
# 設計上の問いに答えるプロトタイプ

この文書は実行用のSKILL.mdではなく、固定した上流版の日本語リファレンスである。
プロジェクトでの運用は[Matt Pocock スキル運用](../../matt-pocock-skills.md)を優先する。

## 概要

prototypeは、一つの問いへ答えるためのthrowaway codeである。
問いに応じて、logic prototypeまたはUI prototypeのどちらかを選ぶ。

## 使う場面

business logic、状態遷移、data shapeが妥当かを操作して確かめる場合はlogic prototypeを使う。
画面の見た目や構造について複数案を比較する場合はUI prototypeを使う。

## 入力と前提

利用者の依頼と周辺codeから、答える問いを一つ特定する。
問いが曖昧なら、利用者へ確認する。
利用者へ確認できない場合は、backend moduleならlogic、pageまたはcomponentならUIを選び、その仮定をprototype冒頭へ記す。

## 手順

1. 最初からthrowawayであることが分かる名前を付け、実際の利用箇所に近い場所へ置く。
2. throwaway UI routeでは、既存のrouting規約に従い、新しいtop-level構造を作らない。
3. UI prototypeはprojectのtask runnerから一つのcommandで起動できるようにし、logic demoはdouble-clickで開く単一HTMLにする。
4. stateは既定でmemoryに置き、persistenceを前提にしない。
5. 問いがdatabaseを明示的に含む場合だけ、scratch databaseまたは`PROTOTYPE, wipe me`と明示したlocal fileを使う。
6. prototypeを実行可能にする範囲を超えるtest、error handling、abstraction、polishを追加しない。
7. logicではactionごと、UIではvariant切替ごとに、関係するstate全体を表示する。
8. 問いへ答えたら、検証済みの判断をreal codeへ取り込む。
9. prototype全体をmain外のthrowaway branchへcommitし、implementation issueからbranchへのcontext pointerを残す。
10. 問いと結論もissueまたはcommitへ記録し、mainには検証済みの判断だけを残す。

## ロジックを検証する手順

logic prototypeは、誰でもbuttonでstate modelを動かせる、自己完結した単一HTMLである。
紙上では判断しにくいbusiness logic、状態遷移、data shape、API shapeを確かめる場合に選ぶ。
見た目を決める問いには使わない。

### 問いの表示

対象のstate modelと問いを一段落で書き、commentだけではなくdemo上部の見える位置へ表示する。
後から見た人も、prototypeが正しい問いへ答えたか確認できるようにする。

### 移植可能なlogic

問いへ答えるlogicを、一つの`<script>` block内の小さく純粋なmoduleとして分離する。
問いに合わせて、純粋なreducer、明示的なstate machine、plain dataを扱う純粋関数群、または内部stateを本当に所有するclassかmoduleを選ぶ。
pageへの接続しやすさではなく、問いへの適合で形を選ぶ。

logicからDOM、`document`、button handlerへの参照を除く。
pageからlogicを呼び出し、logicからpageへは依存させない。
pageはthrowawayだが、検証済みのlogicはreal moduleへ移せる形を保つ。

### 単一HTMLの構成

framework、bundler、serverを使わず、HTML、CSS、JavaScriptをすべて一つのfileへinline化する。
非開発者向けにdomain languageを使い、code上の名前ではなく業務上の意味でlabelとstateを説明する。

上から次の順に構成する。

1. titleと、調べる問いを示す一行の説明を置く。
2. 関係する現在state全体をraw JSONではなく読みやすいfieldとして表示し、clickごとに再描画する。
3. actionごとに常時使えるfree-play buttonを置き、任意の順でmodelを操作できるようにする。
4. scenarioごとのtabを持つguided walkthroughを置く。
5. 各walkthroughへ状況と注目点の短い説明、押す順序に並べた実動作buttonを置く。
6. walkthrough開始時に既知のinitial stateへresetし、毎回同じscenarioを再現する。

scenarioにはhappy path、判断しにくいedge case、許可されない操作の試行を含める。
見た目はclean typography、広いspacing、一つのaccent colorにとどめ、animationやgimmickを使わない。

利用者へfileを渡すか開き、必要に応じてactionやscenarioを追加する。
問いへ答えたら、検証済みのreducer、machine、またはfunction setをreal moduleへ移し、HTML shellをthrowaway branchへ保存する。

## UIを比較する手順

UI prototypeは、一つのroute上へ構造が大きく異なる複数variantを作り、画面下部のswitcherで切り替える。
logicやstateの妥当性を問う場合には使わない。

### 既存pageへの配置

既存page内へ置くsub-shape Aを強く優先する。
同じroute上で`?variant=`によりrenderingだけを切り替え、既存のdata fetching、parameter、authenticationを保つ。
新要素が既存page内へ自然に入る場合もsub-shape Aを選ぶ。

近くに置ける既存pageが本当にない場合だけ、新しいthrowaway routeを作るsub-shape Bを使う。
既存のrouting規約に従い、pathまたはfilenameへ`prototype`を含めるなど、throwawayだと明示する。
sub-shape Bを選ぶ前に、既存pageへ埋め込めないか再確認する。

### variantの作成

既定は3variantとし、5variantを上限にする。
prototypeの場所またはfile冒頭のcommentへ、対象page、variant数、`?variant=`、routeを一行で記す。

各variantは、pageの目的、利用可能なdata、projectのcomponent libraryまたはstyling systemに従う。
`VariantA`、`VariantB`、`VariantC`のような明確なexport名を付ける。
layout、information hierarchy、primary affordanceを変え、色や文言だけの差にしてはならない。
似た案ができた場合は、構造を制約して一案を作り直す。

一つのswitcher componentをrouteへ置き、search parameterに応じてvariantを表示する。
sub-shape Aでは既存data fetchingをswitcherより上に残し、表示subtreeだけを変える。
sub-shape Bでは`/prototype/<name>`配下のthrowaway routeへ同じswitcherを置く。

### 画面下部のswitcher

switcherを画面下部中央へ固定し、前へ戻るarrow、現在variantのlabel、次へ進むarrowを置く。
arrowは端から反対端へ循環する。
click時はframeworkのrouterでURL search parameterを更新し、共有後とreload後もvariantを維持する。
左右arrow keyでも切り替えられるようにするが、`<input>`、`<textarea>`、`[contenteditable]`のfocus中はkeyを奪わない。
switcherは評価対象のpageと見分けられる高contrastな外観にする。
production buildでは表示されないよう、`process.env.NODE_ENV !== 'production'`または同等の条件で保護する。
projectのshared UI領域に一つの共通componentとして置く。

利用者へURLと`?variant=`のkeyを伝える。
案が決まったらvariantと理由を記録し、勝者をreal codeへ取り込み、残りをthrowaway branchへ移す。
sub-shape Aでは勝者を既存pageへ統合し、敗者とswitcherをmainから削除する。
sub-shape Bでは勝者をreal routeへ移し、throwaway routeとswitcherをmainから削除する。

## 成果物

logicの成果物は、自己完結した単一HTMLと、real moduleへ移せる純粋なlogicである。
UIの成果物は、同一route上の構造的に異なるvariant、URLで切り替えられるswitcher、選択された案である。
どちらも、問いと結論、main外のthrowaway branchに残すprototype全体、implementation issueからbranchへのpointerを含む。

## 関連スキル

原文は、ほかの名前付きスキルを明示的には呼び出さない。

## 注意点

logic prototypeへtest、real database、将来向け一般化、framework、bundler、serverを持ち込まない。
logicとDOMを混在させず、HTML shellをproductionへ出さない。
UI variantを色やcopyだけで変えず、variant間でlayoutを共有しすぎない。
UIをreal mutationへ接続せず、必要ならstubを使う。
prototype codeはtestと十分なerror handlingを持たないため、そのままproductionへ昇格させず、取り込み時に書き直す。

## 翻訳元

リポジトリは`mattpocock/skills`である。
固定コミットは`d81f3a183412e71a5b1e84ca21bc1a35eea03a60`である。
原文のskillPathは`skills/engineering/prototype/SKILL.md`である。
同梱資料は`skills/engineering/prototype/LOGIC.md`と`skills/engineering/prototype/UI.md`である。
