---
type: ADR
title: 'ADR-016: API と SPA のメッセージをローカライズする'
description: 機械判定と言語選択を分離するため、API と SPA のメッセージを日本語と英語でローカライズする決定。
tags: [adr, i18n, api, frontend]
---

# ADR-016: API と SPA のメッセージをローカライズする

## Status

Proposed

## Date

2026-09-15

## Context

バックエンドの Problem Details は英語の reason phrase を固定で返し、Bean Validation 用のアプリケーションメッセージもない。
SPA も英語文言を画面へ直書きし、HTML の `lang` を `en` に固定している。

API クライアントは RFC 9457 の `type` で問題種別を判定する契約であり、`title` と `detail` は人が読む文章として変更できる。
言語選択を機械判定と混ぜず、日本語と英語の fallback、HTTP cache、画面の言語属性を揃える必要がある。

## Decision

初期対応言語を日本語（`ja`）と英語（`en`）とし、既定言語を日本語にする。

バックエンドは Spring の `AcceptHeaderLocaleResolver` を使い、`Accept-Language` の優先順位と quality value に従う。
`ja-JP` や `en-US` などは、明示した language-only locale へ fallback する。
ヘッダがない場合と対応言語がない場合は日本語を選ぶ。
URL parameter、Cookie、server の既定 locale では言語を切り替えない。

MVC dispatch 内では `AcceptHeaderLocaleResolver` が locale を解決する。
Spring Security の `AuthenticationEntryPoint` と `AccessDeniedHandler` は `DispatcherServlet` より前に動くため、この経路では同じ対応言語と既定言語を持つ `LocaleSupport` が request header を直接解決する。
二経路は実行位置だけが異なり、対応言語と fallback 規則を共有する。

バックエンドの文言は Spring Boot が自動構成する `MessageSource` へ置く。
基底 bundle を英語、`messages_ja.properties` を日本語とし、system locale への fallback を無効にする。
Bean Validation の業務向け制約は `{validation.required}` のような key を指定し、入力値をメッセージへ埋め込まない。

Problem Details の `type`、`status` と拡張フィールド名はロケールで変えない。
一般的な `about:blank` の HTTP エラーは `title` だけを翻訳し、framework が生成した `detail` は公開しない。
業務固有の安全な `detail` と検証エラーの説明は翻訳する。
クライアントは引き続き `type` で分岐し、翻訳文を分岐条件にしない。
応答には選択した locale の `Content-Language` と `Vary: Accept-Language` を付ける。

SPA は React の i18n で最も広く使われている i18next と react-i18next を使う。
文言は言語ごとの JSON resource に置き、既定言語の resource から message key の型を導く。
`navigator.languages` を優先順に解決し、language subtag が `ja` または `en` に一致しなければ日本語へ fallback する。
i18next の `supportedLngs` による解決は完全一致する候補を優先順より先に選ぶため、言語の解決は自前の関数で行い、結果を i18next の `lng` へ渡す。
同じ理由で、Cookie や localStorage を読み書きする `i18next-browser-languagedetector` は使わない。
i18next の global instance は使わず、`createInstance` で作った instance を `I18nextProvider` で渡す。
起動処理と component test が同じ instance を明示的に受け渡せ、初期化のための副作用 import が不要になるためである。
選択結果を `document.documentElement.lang` と画面 title に反映する。
数値と複数形は翻訳済みの固定文字列へ変換せず、i18next の formatting と plural を使う（どちらも内部で `Intl.NumberFormat` と `Intl.PluralRules` を使う）。
日時は表示境界で `Intl.DateTimeFormat` を使う。

ブラウザ設定を使う間は、API request の `Accept-Language` を JavaScript で上書きしない。
将来、アプリ内の言語選択を追加する場合は、その選択を SPA と API の双方へ同じ規則で反映する。

SPA が Problem Details を扱う際は、既知の `type` をローカル message key へ写像する。
未知の `type` は一般エラーとして扱い、サーバーの `detail` をそのまま HTML へ挿入しない。
現在は API client が存在しないため、未使用の parser は先に作らず、最初の API client と同時にこの規則を実装する。

## Consequences

### Positive

- API と SPA が同じ二言語と fallback 規則を使える。
- Problem Details の機械判定を安定させたまま、人が読む文章を翻訳できる。
- SPA の文言を JSON resource に置くため、翻訳管理サービスや抽出ツールなど i18next の周辺ツールをそのまま使える。
- 複数形、interpolation、namespace による分割、遅延ロードへ、移行せずに拡張できる。
- `Content-Language` と `Vary` により、client と HTTP cache が応答言語を区別できる。

### Negative

- message key と各言語の resource を同じ変更で更新する必要がある。
- 型検査は英語の resource に不足する key を検出するが、余分な key は検出しない。
- SPA に i18next と react-i18next の依存と bundle サイズが加わる。
- 既定言語を日本語にするため、`Accept-Language` を送らない既存テストと client の表示文言が変わる。

### Neutral

- API の `type` URI と OpenAPI schema はローカライズしない。
- 業務固有の検証 problem type と `errors` schema は、最初の業務 API と管理ドメインが確定した時点で ADR-013 に従って追加する。
  [ADR-058](ADR-058-use-path-absolute-relative-uri-for-problem-types.md) で、管理ドメインを待たずに追加した。
- 翻訳の追加は API version を上げる変更ではない。

## Alternatives Considered

### Alternative 1: API は英語だけにする

- Description：SPA だけを翻訳し、バックエンドは英語の Problem Details と検証文言を返す。
- Pros：バックエンドの bundle と locale 解決が不要になる。
- Cons：直接表示する検証文言と API エラーが画面言語と一致せず、client 側で全エラーを再定義する必要がある。

### Alternative 2: 型付き object と `Intl` だけで SPA を翻訳する

- Description：依存を追加せず、TypeScript の型付き object を message catalog にし、数値は `Intl.NumberFormat` で整形する。
- Pros：依存と bundle サイズが増えず、静的な二言語と少数の文言なら足りる。
- Cons：複数形、interpolation、翻訳管理の仕組みを自前で作る必要があり、翻訳ツールと resource の形式を共有できない。

### Alternative 3: 翻訳済み `detail` でエラーを分類する

- Description：SPA が Problem Details の文章を比較して画面を分岐する。
- Pros：追加の problem type を定義せずに実装できる。
- Cons：翻訳と文言修正が client の制御フローを壊し、ADR-013 の契約にも反する。

### Alternative 4: react-intl（FormatJS）を使う

- Description：ICU MessageFormat を標準とする react-intl を使う。
- Pros：ICU MessageFormat をそのまま書け、翻訳管理サービスとの互換性が高い。
- Cons：React での採用例と周辺ツールは i18next より少ない。i18next も plugin で ICU MessageFormat を使えるため、ICU が必要になった時点で移行せずに対応できる。

## References

- [RFC 9110: Accept-Language](https://www.rfc-editor.org/rfc/rfc9110.html#name-accept-language)
- [RFC 9110: Content-Language](https://www.rfc-editor.org/rfc/rfc9110.html#name-content-language)
- [RFC 9457: Problem Details for HTTP APIs](https://datatracker.ietf.org/doc/html/rfc9457)
- [Spring Framework: `AcceptHeaderLocaleResolver`](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/web/servlet/i18n/AcceptHeaderLocaleResolver.html)
- [MDN: `Intl`](https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/Intl)
- [i18next: TypeScript](https://www.i18next.com/overview/typescript)
- [i18next: Formatting](https://www.i18next.com/translation-function/formatting)
- [react-i18next](https://react.i18next.com/)
- [ADR-013](ADR-013-standardize-http-api-contracts.md)
- [ADR-058](ADR-058-use-path-absolute-relative-uri-for-problem-types.md)
- `backend/src/main/resources/application.yaml`
- `frontend/src/i18n/index.ts`
