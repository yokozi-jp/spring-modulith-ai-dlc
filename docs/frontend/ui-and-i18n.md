---
type: Convention
title: フロントエンドのUI、スタイル、国際化
description: UI primitive と業務 component の置き場所、アクセシビリティ、style の配置、表示文言の国際化、日付だけや時刻だけの値の表示を定める。component、style、表示文言、日時の表示を追加または変更するときに読む。
tags: [convention, frontend, ui, accessibility, i18n]
---

# フロントエンドのUI、スタイル、国際化

feature に依存しない UI primitive だけを `components/ui` に置き、shadcn の Base UI 版と Tailwind CSS で実装する。
native semantics と Base UI のアクセシビリティを維持する。
表示文言は `i18n.ts` を介して取得する。
絶対時刻の表示は日時規約に従い、日付だけや時刻だけの値は UTC 変換しない。

## UIとスタイル

featureに依存しないUI primitiveは `components/ui` に置き、shadcnのBase UI版とTailwind CSSで実装する。

業務データ、API型、feature固有の文言を扱うcomponentは `components/ui` に置かない。

共有primitiveを組み合わせた業務componentは、利用するfeatureの `components` に置く。

HTMLの意味と標準操作を優先し、button、label、heading、tableなどのnative semanticsを不要な `div` とARIAで置き換えない。

Base UIが提供するkeyboard操作、focus管理、ARIA属性を維持し、見た目のためにアクセシビリティを削らない。

共通のglobal styleとTailwindの読込は `style.css` に置き、feature固有styleは対象componentの近くへ置く。

## 国際化

利用者へ表示する文言は、現在の `i18n.ts` が提供するlocale解決とmessage catalogを介して取得する。

componentへ同じ表示文言を重複して埋め込まない。

APIのProblem Detailsはバックエンドが解決した利用者向け文言として扱い、フロントエンドの固定文言と混在させない。

message catalogが複数の責務へ分かれる規模になるまでは、空のlocale別ディレクトリへ分割しない。

## 日時表示

APIから受け取る絶対時刻の解析と表示は、[日時とタイムゾーンの規約](../datetime/timezone-conventions.md) の「フロントエンド」に従う。

日付だけの値と時刻だけの値は絶対時刻としてUTC変換せず、API契約の文字列表現をその意味のまま扱う。

## 関連資料

- [ADR-025: shadcn/uiのBase UI版とTailwind CSSを採用する](../adr/ADR-025-adopt-shadcn-base-ui-and-tailwind.md)
