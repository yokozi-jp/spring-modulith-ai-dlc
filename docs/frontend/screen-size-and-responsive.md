---
type: Convention
title: 画面サイズとレスポンシブ対応
description: 想定解像度、windowとzoom、viewport、breakpoint、モバイルファーストのCSS、項目の多い画面のモバイル対応の進め方を定める規約。画面のlayoutを設計するとき、breakpointやresponsiveなstyleを追加するときに読む。
tags: [convention, frontend, responsive, tailwind, future-arch-guidelines]
---

# 画面サイズとレスポンシブ対応

想定解像度を論理解像度で一つ決めてその解像度へ最適化し、それより大きい画面では操作と情報が欠けないことだけを保証する。
CSSはモバイルファーストで書き、page全体のlayoutはmedia query、component単位の調整はcontainer queryで行う。
breakpointはTailwind CSSの既定のprefixを使う。

## 想定解像度

- 利用するデバイス（スマートフォン、タブレット、PC、その他）を洗い出し、それぞれの物理解像度と既定のscaleから論理解像度を求める。
- 設計とテストの基準にする想定解像度は論理解像度で一つに定め、「1366×768以上」のような範囲で書かない。
- 想定解像度では、一部の表を除き、業務に必要な項目を横scrollなしで表示する。
- 想定解像度より大きい画面では、操作できなくなることと情報が隠れることがないようにだけ保証し、大画面向けのlayout調整をしない。
- browserのwindowはfull screenを想定し、zoomは100%を想定する。
- 90%や110%のzoomでも、情報が隠れず操作を続けられるようにする。
- windowの縮小やzoomで想定解像度を下回った場合は、はみ出した部分をscroll barで表示する。
- モバイルの画面回転は要件として合意を取り、縦向きを前提にする。横向きに対応しない場合は、向きの固定を検討する。

## viewport

`index.html` のviewportは `width=device-width, initial-scale=1.0` にする。
固定幅の `width`、1以外の `initial-scale`、`minimum-scale`、`maximum-scale`、`user-scalable` を指定しない。

## breakpoint

- navigationの切替、font size、余白など、page全体のlayoutはviewportを基準にするmedia queryで調整する。
- cardやwidgetなど、componentの調整はcontainer queryで行い、media queryで大枠を決めた後に適用する。
- breakpointはlayoutを変える必要がある箇所だけに置く。
- container queryの入れ子は2段までにする。
- Tailwind CSSの既定のprefix（media queryの `sm`、`md`、`lg`、`xl`、`2xl` と、container queryの `@sm` など）を使う。
- 既定の幅を調整する場合は、`style.css` の `@theme` で意味が変わらない範囲の微修正にとどめる（`sm` を1280pxにしない）。
- breakpointを追加する場合は、`3xl` のような既定の延長か、`tablet` のように意味が分かる名前にする。
- 既定のprefixの値を上書きするのは、開発の初期に対象デバイスが確定した場合だけにする。確定しない場合は別名のprefixを追加する。
- container queryの割合で指定する場合は、物理方向の `cqw` と `cqh` ではなく、論理方向の `cqi` と `cqb` を使う。

## モバイルファースト

- prefixなしのutilityをモバイル向けの指定にし、大きいbreakpointのprefixで上書きする（`text-center sm:text-left`）。
- `sm:` をモバイル向けの指定に使わない。
- モバイルの要件がない画面でも、この書き方を守る。

## 項目の多い画面のモバイル対応

PC向けの業務画面をモバイルへ対応させる場合は、次の順で方式を選ぶ。

1. モバイルで行う業務のユースケースを絞り、そのユースケースの画面遷移に含まれる画面だけを対象にする。
2. 表示する項目を減らせないか検討する。
3. 減らせない場合は、同じ画面の中で表示を切り替える（PCでは表、モバイルではlist）。
4. 切替で対応できず、画面の概念として別画面が自然な場合だけ、モバイル専用の画面を作る。
5. 通知、オフライン、カメラなど、モバイルに特化したユースケースが出た場合だけ、モバイルアプリを検討する。

## 出典

- フューチャー株式会社「Webフロントエンド設計ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forWebFrontend/web_frontend_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
