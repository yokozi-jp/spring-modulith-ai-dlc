# ADR-030: Base UIのインラインstyle要素を限定して許可する

## Status

Proposed

## Date

2026-09-29

## Context

ADR-014はSPAのCSPを最終的なHTML配信点で保証し、`style-src 'self'` によってインラインstyleを拒否する方針を定めた。
ADR-025で採用するBase UIの一部componentは、native scrollbarの抑止などに固定内容の `<style>` 要素を実行時に生成する。

S3などから静的SPAを配信する構成では、requestごとのnonceをHTMLへ埋め込めない。
`CSPProvider disableStyleElements` で生成を止める方法はstrict CSPを維持できるが、Base UIが生成していたCSSをapplication側で複製し、dependency更新時に同期する必要がある。
CSP hashも静的配信に利用できるが、Base UIがCSSの内容や空白を変えるたびに配信設定との同期が必要になる。

本プロジェクトは保守性と設定の単純さを優先しながら、任意HTMLとinline scriptの実行経路は引き続き遮断する。
CSP Level 3はstyle要素とstyle属性を `style-src-elem` と `style-src-attr` で分離できる。
Frontendのformat、lint、型検査はVite+の `vp check` へ統一しており、別の静的解析製品を任意HTML禁止の必須経路にすると設定と実行環境が増える。

## Decision

SPAのCSPは `style-src 'self'` を非対応browser向けfallbackとして維持し、`style-src-elem 'self' 'unsafe-inline'` でinline `<style>` 要素を許可する。
HTMLとして解釈されるstyle属性は `style-src-attr 'none'` で拒否する。
`script-src 'self'` は維持し、inline scriptと `unsafe-eval` は許可しない。

Base UIには必要なstyle要素を生成させるため、application rootで `CSPProvider disableStyleElements` を使わない。
Base UIが生成するCSSをapplicationへ複製せず、CSP hashやnonceも導入しない。

任意HTMLを直接描画する `dangerouslySetInnerHTML` はOxlintの `react/no-danger` で禁止する。
`innerHTML`、`outerHTML`、`insertAdjacentHTML`、`document.write`、`document.writeln`、`createContextualFragment`、`DOMParser`、`parseHTMLUnsafe`、`setHTMLUnsafe`、iframeのDOM `srcdoc` はOxlintの `no-restricted-properties` と `no-restricted-globals` で禁止する。
JSXの `srcDoc` はrepository-localのOxlint JavaScript pluginで禁止する。
これらの規則は `vp check` から実行し、Frontend CIとpre-commitでblockingにする。
ESLintとSemgrepへ同じ禁止規則を重複定義しない。

文字列はReact childrenまたは `textContent` として描画する。
業務要件としてHTML描画が必要になった場合は、個別のlint抑制を追加せず、sanitizerと単一の描画境界を別途設計する。

本番では、SPAのHTMLを最終的に返すCDN、reverse proxyまたはSpring BootがこのCSPの正本となる。
複数層から同じヘッダを重複して付けない。

## Consequences

### Positive

- Base UIのCSSをapplication側へ複製せず、componentとdependency更新へ追従できる。
- 静的SPA配信のcacheを維持し、requestごとのnonce生成を必要としない。
- style要素とstyle属性を分け、必要な許可をstyle要素へ限定できる。
- 任意HTML sinkを既存のOxlintと `vp check` で遮断し、専用scannerの実行環境を必要としない。
- ローカル、pre-commit、Frontend CIが同じ規則と入口を使う。

### Negative

- Base UI以外が注入したinline `<style>` 要素もbrowserに許可される。
- CSS injectionが成立すると、警告の非表示や操作要素の重ね合わせなど、画面の完全性を損なう可能性がある。
- Reactのescaping、HTML sinkの静的解析、scriptと外部通信先のCSP制限は危険を減らすが、inline style要素を拒否する防御と同等ではない。
- CSP Level 3の分離directiveに対応しないbrowserでは、fallbackの `style-src 'self'` によってBase UIのinline style要素が拒否される。
- OxlintのJavaScript plugin APIはalphaであり、Oxlint更新時にrepository-local ruleの互換性を検証する必要がある。

### Neutral

- JavaScriptがDOMの個別style propertyへ設定する動的な位置や寸法は、この判断の対象外である。
- `srcDoc` 以外はOxlintの組み込み規則を使い、独自ruleの範囲を一つのJSX属性に限定する。
- Semgrepのcommunity規則によるadvisory scanは継続するが、任意HTML禁止の必須経路にはしない。
- Trusted Typesはbrowser互換性と依存libraryへの影響を確認してから別途判断する。

## Alternatives Considered

### CSPProviderと外部CSSを使う

- **Description**：Base UIのstyle要素を止め、必要なCSSをapplication所有のstylesheetへ移す。
- **Pros**：inline style要素を拒否するstrict CSPを維持できる。
- **Cons**：Base UIの内部CSSとapplication側の複製をdependency更新ごとに同期する必要がある。

### CSP hashを使う

- **Description**：Base UIが生成する既知のstyle内容だけをSHA-256 hashで許可する。
- **Pros**：任意のinline style要素を許可せず、静的配信を維持できる。
- **Cons**：Base UIのCSS変更ごとにhashと配信設定を同期し、実browserで一致を検証する必要がある。

### Requestごとのnonceを使う

- **Description**：配信点でnonceを生成し、CSP headerとBase UIへ同じ値を渡す。
- **Pros**：許可したstyle要素だけをbrowserが適用する。
- **Cons**：静的HTMLへrequestごとの値を埋め込む配信処理が必要になり、S3とCDNの単純なcache配信を失う。

### style-src全体でunsafe-inlineを許可する

- **Description**：`style-src 'self' 'unsafe-inline'` だけを指定する。
- **Pros**：設定が短く、古いbrowserでもBase UIのstyle要素が動作する。
- **Cons**：style要素とstyle属性の両方を許可し、必要以上にCSPを緩和する。

### Semgrepで任意HTML sinkを禁止する

- **Description**：repository固有のSemgrep規則とfixtureを追加し、CIでblockingにする。
- **Pros**：複数言語を同じ構造検索エンジンで検査できる。
- **Cons**：Frontendの既存lintとは別の規則、runtime、CI stepを管理し、Semgrepを利用できないprojectへ方針を移植しにくい。

### ESLintを併用する

- **Description**：ESLintの組み込み規則とAST selectorで任意HTML sinkを禁止する。
- **Pros**：成熟したplugin APIと豊富な既存規則を利用できる。
- **Cons**：Oxlintと対象ファイル、規則、実行入口を二重管理することになる。

## References

- [ADR-014: SPAとバックエンドを同一オリジンで公開する](./ADR-014-use-same-origin-spa-security-boundary.md)
- [ADR-025: shadcn/uiのBase UI版とTailwind CSSを採用する](./ADR-025-adopt-shadcn-base-ui-and-tailwind.md)
- [Base UI: CSP Provider](https://base-ui.com/react/utils/csp-provider)
- [MDN: `style-src-elem`](https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Content-Security-Policy/style-src-elem)
- [MDN: `style-src-attr`](https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Content-Security-Policy/style-src-attr)
- [Oxlint: `react/no-danger`](https://oxc.rs/docs/guide/usage/linter/rules/react/no-danger)
- [Oxlint: `no-restricted-properties`](https://oxc.rs/docs/guide/usage/linter/rules/eslint/no-restricted-properties)
- [Oxlint: JavaScript Plugins](https://oxc.rs/docs/guide/usage/linter/js-plugins)
- [OWASP: DOM based XSS Prevention Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/DOM_based_XSS_Prevention_Cheat_Sheet.html)
