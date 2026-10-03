---
type: ADR
title: 'ADR-047: フロントエンドで API の日時を Temporal で解析する'
description: API の絶対時刻を Temporal.Instant、日付だけの値を Temporal.PlainDate で解析し、Date で解析しない決定と、Safari 向けポリフィルを最初の日時画面で追加する方針。
tags: [adr, datetime, timezone, frontend]
---

# ADR-047: フロントエンドで API の日時を Temporal で解析する

## Status

Proposed

## Date

2026-10-03

## Context

APIは絶対時刻を`Instant.toString()`と同じ形式で返し、小数部は0桁、3桁、6桁の可変長になる。
ECMAScriptの日時文字列形式は小数部を3桁と定め、それ以外の文字列の解釈は実装に任せている（[ECMA-262, `Date.parse`](https://tc39.es/ecma262/multipage/numbers-and-dates.html#sec-date.parse)）。
`Date`はミリ秒までしか持たないため、受け取った値を送り返すと精度が落ちる。
日付だけの文字列は`new Date("2026-10-01")`がUTCの0時と解釈するため、UTCより西の地域で表示すると前日になる。

Temporalは2026年3月にStage 4になり、ChromeとFirefoxは出荷済みである（[proposal-temporal](https://github.com/tc39/proposal-temporal)）。
2026年10月時点でSafariは未出荷で、BaselineはLimited availabilityである（[Web Platform Status, Temporal](https://webstatus.dev/features/temporal)）。
[対応ブラウザとWeb機能の採用基準](../frontend/browser-support.md)はiOSのSafariを対象に含め、Limited availabilityの機能を本番のコードで使わないと定めている。

現時点のフロントエンドには日時を扱う画面がなく、日時ライブラリもポリフィルも依存にない。

## Decision

APIの絶対時刻は`Temporal.Instant.from(...)`で解析し、`toLocaleString`で表示する。
日付だけの値は`Temporal.PlainDate.from(...)`で解析する。
APIの値を`new Date(string)`や`Date.parse`で解析しない。

Safariを対応ブラウザに含める間は、ポリフィルを使う。
候補は、proposal-temporalのREADMEが安定版と位置づける[temporal-polyfill](https://www.npmjs.com/package/temporal-polyfill)とする。
依存は日時を扱う最初の画面と同じ変更で追加し、それまでは追加しない。
SafariがTemporalを出荷し、対応ブラウザとWeb機能の採用基準で使える状態になったら、ポリフィルを外す。

## Consequences

### Positive

- マイクロ秒の値を、実装依存の解釈や精度落ちなしに扱える。
- 日付だけの値が、表示するタイムゾーンで前日にずれない。

### Negative

- ポリフィルを入れると、配信するJavaScriptが増える。
- ポリフィルのオブジェクトはブラウザの`Intl.DateTimeFormat`が直接受け付けない場合があり、表示は`toLocaleString`に寄せる必要がある。

### Neutral

- ポリフィルを使う間は、ブラウザのTemporalではなくライブラリに依存するため、Limited availabilityの機能を直接使うことにはならない。
- ポリフィルを外す時期は、SafariのTemporal出荷後に判断する。

## Alternatives Considered

### 選択肢1: `Date`を表示専用にし、送り返す値は文字列のまま持つ

- **Description**：`Date`で解析して表示だけに使い、比較やAPIへの送信には受け取った文字列を使う。
- **Pros**：依存を増やさない。
- **Cons**：6桁の小数部の解析は仕様で保証されず、日付だけの値と比較の規則を別に定める必要がある。

### 選択肢2: date-fnsやDay.jsなどの日時ライブラリを使う

- **Description**：既存の日時ライブラリで解析と表示を行う。
- **Pros**：Safariでもそのまま動く。
- **Cons**：標準のTemporalへ移る段階で書き換えが要る。多くは内部で`Date`を使い、ミリ秒までしか持たない。

## References

- [日時とタイムゾーンの規約](../datetime/timezone-conventions.md)
- [対応ブラウザとWeb機能の採用基準](../frontend/browser-support.md)
- [ADR-006: 絶対時刻を UTC / Instant / timestamptz に統一する](ADR-006-utc-instant-absolute-time-policy.md)
- TC39, Temporal: <https://github.com/tc39/proposal-temporal>
- npm, temporal-polyfill: <https://www.npmjs.com/package/temporal-polyfill>
