# ADR-025: shadcn/uiのBase UI版とTailwind CSSを採用する

## Status

Proposed

## Date

2026-09-29

## Context

FrontendはReact 19とTanStack Routerを採用しているが、共有UI componentとstyling systemをまだ持っていない。
現在の画面はViteの初期画面に近く、今後追加する業務画面でbutton、form field、dialog、selectなどの見た目と操作を統一する必要がある。

対話componentを個別に実装すると、keyboard操作、focus管理、ARIA属性を画面ごとに設計して検証することになる。
完成済みのthemeを持つcomponent libraryをそのまま採用すると、application固有のdesign tokenへ合わせるためにlibraryのtheme APIと上書き規則へ依存する。

shadcn/uiはnpm packageから完成済みcomponentをimportする方式ではなく、component sourceをrepositoryへ配置してapplication側で所有するcode distribution方式である。
shadcn/uiのBase UI版は、unstyledでアクセシビリティを重視するBase UIを対話primitiveに使い、Tailwind CSSで見た目を構成する。
Tailwind CSS v4はVite pluginでbuild時に静的CSSを生成できる。

ADR-014は本番SPAへ `script-src 'self'` と `style-src 'self'` のContent Security Policyを要求し、inline scriptとinline style elementを許可していない。
Base UIの一部componentはscrollbar制御などのためにinline style elementを生成するため、既定のままでは本番CSPに拒否される可能性がある。
Base UIは `CSPProvider` によって、配下のcomponentが生成するstyle elementを無効化できる。

ADR-023はform状態にTanStack Form、runtime入力検証にZodを使う判断を記録している。
shadcn/uiのTanStack Form例もZodを使い、ADR-024で採用するOrvalはOpenAPIからZod schemaを生成できる。
UI component、form、生成API clientで同じschema libraryを使えば、schema記法と依存更新を一系統にできる。

## Decision

共有UI componentのcode distributionにshadcn/uiのBase UI版を採用し、対話primitiveにBase UI、styling systemにTailwind CSS v4を使う。
shadcn/uiが配置するcomponent sourceは `frontend/src/components/ui` でapplication codeとして管理し、必要なcomponentだけを追加する。
featureとrouteはBase UIを直接importせず、`components/ui` の公開componentを使う。

色、余白、角丸、focus ringなどの共通値はCSSのsemantic tokenとして定義し、componentから具体的な色へ直接依存しない。
Tailwind CSSと別のstyling systemをcomponent単位で併用しない。
既存の初期画面用CSSは、共有UI componentを導入する変更でTailwind CSSへ置き換える。

application rootを `CSPProvider` の `disableStyleElements` 設定で囲み、Base UIによるinline style elementの生成を無効化する。
不足する見た目は静的stylesheetへ定義し、ADR-014の `style-src 'self'` を緩和しない。
inline scriptを必要とするBase UI機能は先行して有効化しない。
その機能が必要になった場合は、配信点でrequestごとのnonceを生成してCSP headerとapplicationへ同じ値を渡す設計を別途行う。

form状態と入力検証には、ADR-023どおりTanStack FormとZodを使う。
shadcn/uiの利用例だけを理由にReact Hook Formを追加せず、ValibotとZodを併用しない。
依存は検証したexact versionへ固定する。

## Consequences

### Positive

- DialogやSelectなどの対話componentで、Base UIのkeyboard操作、focus管理、ARIA対応を利用できる。
- component sourceをrepository内で確認し、applicationの要件に合わせて変更できる。
- Tailwind CSSが生成する静的CSSを使い、ADR-014のstrict CSPを維持できる。
- semantic tokenを通じて、共有componentと業務画面の見た目を統一できる。
- TanStack Form、shadcn/uiの公開例、Orvalの生成schemaでZodを共通利用できる。

### Negative

- 取り込んだshadcn/ui componentのsourceと上流変更をapplication側で保守する必要がある。
- utility classがcomponent markupへ現れるため、class構成とsemantic tokenのreview規約が必要になる。
- `disableStyleElements` の影響を受けるBase UI componentでは、scrollbarなどのstyleを静的CSSで補う必要がある。
- component libraryを更新するとき、型検査だけでなくkeyboard操作、focus、CSP下の動作を回帰検証する必要がある。

### Neutral

- shadcn/uiはcomponent sourceをrepositoryへ配置し、CLIと共通Tailwind CSSは `shadcn` packageをbuild時に使う。
- Base UIとTailwind CSSの型やclassをfeature modelへ持ち込まないが、UI component内部では直接利用する。
- Data Grid、chart、rich text editorなどのspecialized componentは、このADRで選定しない。
- 共有component catalogは、component数と状態variationが増えてから必要性を判断する。

## Alternatives Considered

### Base UIとplain CSSを直接使う

- **Description**：shadcn/uiを使わず、Base UI primitiveと独自CSSから全共有componentを実装する。
- **Pros**：配布されたcomponent sourceへの追従が不要で、styling方式を完全に制御できる。
- **Cons**：button、field、dialogなどの見た目とvariantを一から設計する作業が増え、現段階では同等の独自実装を持つ理由がない。

### MUIを採用する

- **Description**：Material UIの完成済みcomponentとtheme systemを使う。
- **Pros**：componentの範囲が広く、Material Designを前提とする画面を短期間で構築できる。
- **Cons**：Material DesignとMUIのtheme APIがapplication全体の前提になり、標準のEmotion構成ではstrict CSPのためにrequestごとのnonce設計が必要になる。

### Radix PrimitivesとCSS Modulesを採用する

- **Description**：Radix Primitivesを対話primitiveにし、componentごとのCSS Modulesで見た目を実装する。
- **Pros**：成熟したprimitiveを使い、utility classをmarkupへ記述せずに済む。
- **Cons**：共有componentのvariantとdesign tokenを独自に構成する量が増え、新規shadcn/ui projectが既定とするBase UI版のcomponent sourceを利用できない。

### Valibotを使う

- **Description**：TanStack Formのruntime入力検証にValibotを使う。
- **Pros**：機能単位のimportとtree shakingによって、client bundleを小さくしやすい。
- **Cons**：shadcn/uiの公開例とOrvalが生成するZod schemaに加えて、二つのschema libraryとエラー処理を保守することになる。

## References

- [ADR-014: SPAとバックエンドを同一オリジンで公開する](./ADR-014-use-same-origin-spa-security-boundary.md)
- [ADR-023: TanStack FormとZodを採用する](./ADR-023-adopt-tanstack-form-and-zod.md)
- [ADR-024: Frontend API client生成にOrvalを採用する](./ADR-024-adopt-orval-for-frontend-api-client.md)
- [shadcn/ui: Introduction](https://ui.shadcn.com/docs)
- [shadcn/ui: Base UI as the Default](https://ui.shadcn.com/docs/changelog/2026-07-base-ui-default)
- [Base UI: About](https://base-ui.com/react/overview/about)
- [Base UI: CSP Provider](https://base-ui.com/react/utils/csp-provider)
- [Tailwind CSS: Using Vite](https://tailwindcss.com/docs/installation/using-vite)
