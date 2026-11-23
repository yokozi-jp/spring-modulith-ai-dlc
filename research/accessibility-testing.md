# アクセシビリティを検査で止める（#335 の調査）

この文書は #335 の調査の記録で、merge しない。
結論は #335 のコメントと同じで、作業は #647 と #648 で行う。
向こうは `yokozi-jp/agent-software-factory`（`origin/main`、commit `3ba2640`）、こちらは `yokozi-jp/spring-modulith-ai-dlc`（`origin/main`、commit `ee8bf60`）である。
調べた日は 2026-11-23 で、版は npm と GitHub の Releases で同じ日に確かめた。

## 結論

一部採用とする。

向こうの方式は、Storybook の a11y addon（axe）で、`packages/ui` の部品の story に axe を流して止める形である。
こちらは Storybook を入れない（#284 のまま）。
代わりに、Playwright の E2E に `@axe-core/playwright` を入れ、実ブラウザで、ライトとダークの配色の画面に axe を流して止める。

この結論の根拠は、実測である。
lint（`jsx-a11y`）と jsdom のテストが通っている `origin/main` の画面に axe を流すと、実在の違反が 4 種見つかった。
そのうち `color-contrast` の 2 件は、jsdom の axe では判定不能になり、実ブラウザでだけ見つかる。

取り込むものは 2 つである。

- E2E に axe を入れ、見つかった 4 種の違反を直し、ADR-027 の「axe は要件が生じるまで追加しない」を改める（#647）。
- lint の `jsx-a11y` を、`Link` と `Button` にも効かせる（#648）。

取り込まないものは、Storybook とその a11y addon、story 必須の検査、`"todo"` で始めて警告にとどめる段階、jsdom での axe、Vitest の browser mode である。

## 比べたもの

### 向こう

- `apps/web/.storybook/main.ts`、`preview.ts`
- `apps/web/test/a11y-warnings-reporter.ts`
- `docs/adr/0033-shadcn-base-ui-tailwind-v4-storybook.md`（以下 ADR 0033）、`docs/adr/0039-e2e-playwright-gate-and-ai-for-authoring.md`
- `tools/scripts/src/checks/repo-policy/ui-stories.ts`
- `vite.config.ts`、`docs/web-frontend/testing.md`
- Issue #315、#464、Pull Request #467、#474

### こちら

- `frontend/vite.config.ts`（`jsx-a11y`）、`frontend/apps/web/e2e/`、`frontend/apps/web/playwright.config.ts`
- `docs/frontend/test-strategy.md`、`ui-and-style.md`、`component-design.md`、`form-validation.md`、`dark-mode.md`、`lint-config.md`
- `docs/e2e/testing-strategy.md`、`docs/repository/branch-protection.md`
- ADR-025、ADR-027、ADR-034、ADR-057、ADR-087、ADR-092、ADR-098、ADR-100
- 既に決まった #284（Storybook）、#331（Vitest の browser mode）、#277（E2E を必須にする）、#344（テストの粒度）

## 向こうの構成

向こうの a11y は、Storybook の story に閉じている。

- `.storybook/main.ts:7` が `@storybook/addon-a11y` と `@storybook/addon-vitest` を入れる。
- `.storybook/preview.ts:17-28` が `parameters.a11y.test` の既定を `"error"` にし、`runOnly` に `wcag2a`、`wcag2aa`、`wcag21a`、`wcag21aa`、`wcag22aa`、`best-practice` を並べる。`target-size` は `wcag22aa` だけに付くので、このタグを足さないと動かない（同ファイル `:23-26` のコメント）。
- 最初は `"todo"`（警告）で始め（Pull Request #467）、違反の story が 0 件になったので `"error"` に上げた（Pull Request #474、Issue #464。基準は「違反の story が 0 件で、画面の story が増える前」、ADR 0033:22）。
- 公式の `"todo"` は CI のログに何も出さない（[Storybook: Accessibility tests](https://storybook.js.org/docs/writing-tests/accessibility-testing) の「Automate with CI」）。そのため、Vitest の reporter（`apps/web/test/a11y-warnings-reporter.ts`、69 行）が `a11y warning:` を出す。reporter は addon が書く非公開の構造 `meta().reports` を読む（同ファイル `:1-4`、ADR 0033:32）。
- 検査の対象は `packages/ui` の部品の story（9 つ）と `apps/web/src/app.stories.tsx` である。各部品に story を必須にする検査が `ui-stories.ts:9-24` にある。
- 向こうの lint は、`jsx-a11y` を `plugins: ["react", "jsx-a11y"]` で入れ、Oxlint の既定の規則（correctness）だけが有効である（`vite.config.ts:920-930`）。
- 向こうに Playwright の spec はない。`3ba2640` の tree に `*.spec.ts` は 0 件である。ADR 0039 は e2e（tester.army）を使い、見直す条件に「axe-core を入れられること」を挙げる（`docs/web-frontend/testing.md:116`）。
- 向こうの `docs/web-frontend/testing.md:69-72` は、axe をコンポーネントテストと E2E の両方に入れると書く。この節は Future の設計ガイドラインの写しで、こちらの `test-strategy.md:62` と同じ文面である。

つまり向こうの a11y は、部品を 1 つずつ見る検査で、E2E の axe はまだない。

## こちらの現状

- `frontend/vite.config.ts:105` が `jsx-a11y` を有効にし、全カテゴリを `error` にしている。Oxlint 1.93.0 の `jsx-a11y` は 37 規則で、36 規則が有効になる（`vp lint --rules` で確かめた）。無効の 1 つは nursery の `anchor-ambiguous-text` である。
- `settings` は `shadcn` だけで（`vite.config.ts:316`）、`jsx-a11y` の `components` がない。
- axe の検査はない。ADR-027:67 が「jest-dom、Playwright、axe、fast-check は利用する要件が生じるまで追加しない」と決めた。Playwright は ADR-057 で入れたが、axe は残っている。
- `docs/frontend/test-strategy.md:62` は「axe-core などの自動検査は、ADR-027 のとおり要件が生じた時点で導入し、component test と E2E test に組み込む」と書く。
- `docs/frontend/component-design.md:56` が、操作要素を WCAG 2.2 の 2.5.8（24×24 CSS pixel 以上）にすると決め、`dark-mode.md:17` が明暗の両方で contrast を確認すると決める。どちらも機械では確かめていない。
- E2E は Chromium で、`ja-JP`、`storageState` を共有して流す（`playwright.config.ts:10-25`）。ダークの配色は `prefers-color-scheme` に従う（`packages/ui/src/style.css:41`）ので、Playwright の `colorScheme` で切り替えられる。
- E2E は、今は必須の check ではない（`docs/repository/branch-protection.md:108`）。#277 が `CI gate` の必須にすると決め、作業は #451 で、まだ入っていない。

## 先に決まっていたこと

- #284 は Storybook を見送り、「a11y の自動検査は #335 が、Storybook を前提にせず、`jsx-a11y` と E2E も含めて決める」とした。
- #331 は Vitest の browser mode を見送り、レイアウトに依存する規則（`color-contrast`、`target-size`）は jsdom で検出できないという事実を #335 に渡した。#331 は、選び方に「component test を Chromium で流す方式」と「E2E で `@axe-core/playwright` を使う方式」があり、後者は未測定だと書いた。
- #277 は E2E を `CI gate` の必須にする。

この調査は、#331 が未測定とした「E2E で `@axe-core/playwright` を使う方式」を実画面で測る。

## 実測

### 方法

`origin/main`（`ee8bf60`）の複製に `@axe-core/playwright` 4.14.0（axe-core 4.14.0）を足し、`/verify-demo` の `stack.sh up` が起動する compose-test の stack（slot 0）に Chromium でログインした。
16 の画面と状態に、axe を流した（付録の `a11y-probe.mjs`）。

- 画面：トップ、注文の一覧、状態で絞った一覧、注文の作成、空のまま送信した注文の作成（検証エラー）、シーダーの注文 5 件の詳細（下書き、確定済み 3 件、取消済み）、メンバー、テナントの選択、404、ログアウト後。
- 流れの中の状態：楽観的ロックの競合の選択肢、確定直後（決済の処理中）。
- 組：ライト 1280px、ダーク 1280px、ライト 320px。すべての画面に全部の組を流したのではなく、全部で 34 回流した。
- タグ：`wcag2a`、`wcag2aa`、`wcag21a`、`wcag21aa`、`wcag22aa`、`best-practice`。

同じ stack で、jsdom の component test の中で `axe.run` を流す一時のテストも書いた（付録の `a11y-jsdom-probe.test.tsx`）。
対象はトップ、注文の作成の検証エラー、注文の詳細（下書き）の 3 つで、既存の `renderRoute` と MSW を使った。

### 見つかった違反

| 規則                                                    | 画面                                                          | 内容                                                                                                                                                                                                                                                                               |
| ------------------------------------------------------- | ------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `color-contrast`                                        | トップ（ライト、1280px と 320px）                             | `text-muted-foreground`（#737373）を `bg-muted`（#f5f5f5）に置いた文字が 4.34:1（基準は 4.5:1）。対象は `<div class="rounded-full border bg-muted px-3 py-1 text-sm text-muted-foreground">React 19、TanStack、Base UI</div>`                                                      |
| `color-contrast`                                        | 注文の詳細の下書きと、競合の選択肢（ライト、1280px と 320px） | destructive の Button の `text-destructive`（#e7000b）が `bg-destructive/10`（#fde6e7）の上で 4:1                                                                                                                                                                                  |
| `aria-valid-attr-value`                                 | 注文の作成の検証エラー（3 つの組すべて、2 欄）                | `aria-errormessage="…-error"` が指す要素に、読み上げる手段（`aria-live`、`aria-describedby`、`role="alert"` など）がない。`order-lines-fields.tsx:29-39` と `customer-order-code-field.tsx:50-55` が、`form-validation.md:63` のとおり `aria-errormessage` で `<p>` を参照している |
| `landmark-one-main`、`region`（どちらも best-practice） | 404（ライト）                                                 | `route-not-found.tsx:9-15` が `<section>` で、`<main>` がない。内容が landmark の外にある                                                                                                                                                                                          |

ダークの配色では、`color-contrast` の 2 件が出なかった。
ほかの画面（一覧、確定済みと取消済みの詳細、メンバー、テナントの選択、ログアウト後）は、どの組でも違反が 0 件だった。
`target-size` は、`/orders` の 1280px と 320px で 14 要素、`/orders/new` の 320px で 8 要素が通り、違反はなかった。
トップの 320px では、`color-contrast` が `incomplete`（人が確かめる）にも 1 件出た。

同じ画面の組を 3 回流して、違反の規則、件数、`incomplete` が一致した。
1 回の `analyze()`（タグなし）は 0.33 秒から 2.56 秒で、中央値は 0.65 秒だった（34 回、開発機、他のセッションの負荷あり）。

### 検査ごとの検出

同じ違反を、3 つの検査で比べた。

| 違反                                   | `jsx-a11y`（lint） | jsdom の axe             | Chromium の axe（E2E） |
| -------------------------------------- | ------------------ | ------------------------ | ---------------------- |
| トップの `color-contrast`              | 見つからない       | `incomplete`（判定不能） | 見つかる               |
| destructive の `color-contrast`        | 見つからない       | `incomplete`（判定不能） | 見つかる               |
| `aria-valid-attr-value`                | 見つからない       | 見つかる（2 件）         | 見つかる（2 件）       |
| 404 の `landmark-one-main` と `region` | 見つからない       | 確かめていない           | 見つかる               |

`jsx-a11y` の列は、`origin/main` の `vp lint` が通っていることから決まる（CI が通る commit で、4 種の違反が実在する）。
jsdom の axe は、トップ、検証エラー、詳細の 3 つのすべてで `color-contrast` が `incomplete` だった。
jsdom は Tailwind の CSS を読まず、`HTMLCanvasElement.getContext` も実装しないため、実行ごとに `Not implemented: HTMLCanvasElement's getContext()` が出る。
この結果は、#331 が fixture で確かめた「jsdom は `color-contrast` を判定できず、`target-size` を見逃す」と、実画面でも一致した。

### Playwright 1.63.0 と axe の組

こちらの Playwright は 1.63.0（`apps/web/package.json`）で、`@axe-core/playwright` の peer は `playwright-core >= 1.0.0` である。
lockfile は `4.14.0(playwright-core@1.63.0)` に解決し、そのまま動いた。
Playwright を 1.65.1（最新）に上げる必要はない。

## 項目ごとの比較

| 向こうの規則                                   | 向こうの場所                                                      | こちらの扱い                                            | 判定                       |
| ---------------------------------------------- | ----------------------------------------------------------------- | ------------------------------------------------------- | -------------------------- |
| axe を実ブラウザで流し、`"error"` で止める     | `preview.ts:17-28`、ADR 0033:22                                   | 実画面の E2E で止める（#647）                           | 一部採用（流す場所が違う） |
| WCAG 2.2 の AA と best-practice のタグを並べる | `preview.ts:23-26`                                                | 同じタグを `makeAxeBuilder` に並べる（#647）            | 採用                       |
| `"todo"` で始め、0 件になったら `"error"`      | ADR 0033:22、#467、#474                                           | 4 件を直してから最初から止める（#647）                  | 取り込まない               |
| `"todo"` の違反を出す reporter                 | `a11y-warnings-reporter.ts`                                       | 警告の段階がないので要らない                            | 取り込まない               |
| Storybook の a11y addon                        | `main.ts:7`                                                       | #284 のまま見送る                                       | 取り込まない               |
| 部品に story を必須にする検査                  | `ui-stories.ts:9-24`                                              | #284 のまま見送る（coverage の下限が止める）            | 取り込まない               |
| `eslint-plugin-storybook`                      | ADR 0033:21                                                       | Storybook がないので要らない                            | 当たらない                 |
| `jsx-a11y` を入れる                            | `vite.config.ts:930`                                              | こちらが広い（全カテゴリ）。`components` を足す（#648） | 一部採用                   |
| axe をコンポーネントテストにも入れる           | `docs/web-frontend/testing.md:70`、こちらの `test-strategy.md:62` | jsdom では入れない（下記）                              | 取り込まない               |
| `target-size` を機械で止める                   | `preview.ts:23-26`                                                | E2E の同じタグで止める                                  | 採用                       |
| スマホの幅（320px、390px）で流す               | `preview.ts:29-43`                                                | 組を 1 か所で足せる形にして、#337 の結論を待つ          | 保留（#337）               |

## 取り込むもの

### E2E に axe を入れる（#647）

向こうと同じタグを、実画面の E2E に流す。
理由は 3 つある。

- 見つかった違反の半数（`color-contrast` の 2 件）は、実ブラウザでだけ見つかる。lint と jsdom のどちらでも判定できない。
- 4 件のうち、`packages/ui` の部品の中に原因があるのは destructive の Button だけである。トップの文字の組はページの中、検証エラーは feature の component、404 は route にある。部品を 1 つずつ見る検査は、この 3 件を見ない（Storybook は実行していないので、story に書いた場合の挙動は確かめていない）。
- ダークの配色の contrast は、`dark-mode.md:17` が確認すると決めながら、機械で確かめていない。Playwright の `colorScheme` で切り替えれば、同じ画面を明暗の両方で流せる。

形は、Playwright の公式が示す fixture（`makeAxeBuilder`）である（[Accessibility testing](https://playwright.dev/docs/accessibility-testing)）。
`withTags` に向こうと同じ 6 つを並べる。
タグなしの既定でも、今回の画面では同じ規則が出た（34 回のすべてで、タグ付きにだけ出る違反はなかった）。
それでもタグを明示するのは、止める範囲を WCAG 2.2 の AA と best-practice に書き残すためである。
AAA の規則（`color-contrast-enhanced` など）は既定でも動かない。

流す画面は、`a11y.spec.ts` に画面の一覧を持ち、ライトとダークで流す。
流れの中でしか届かない状態（競合の選択肢、決済の欄）は、既存の spec が届いた時点で同じ補助を呼ぶ。
ログインの Keycloak の画面は、こちらの画面ではないので対象にしない。

止める方式は、最初から失敗にする。
向こうが `"todo"` から始めたのは、Issue #315 が「最初は警告だけにし、様子を見て止める側に上げる」と決めたからである。
止める側に上げた Pull Request #474 の時点で、違反の story は 0 件だった。#467 の時点の件数は確かめていない。
こちらは今の違反が 4 種に限られ、直すのに数行で済む。
警告は CI のログに出ず（公式の記述と向こうの reporter が示す）、読まれないまま残る。
Playwright の `expect(violations).toEqual([])` は、最初から失敗にできる。

E2E が必須の check でないあいだは、a11y の失敗も必須ではない。
E2E が必須になる作業（#451）が入ると、`CI gate` が止める。
E2E の CI は retry が 2 回なので、a11y の失敗が retry で通るかを、受け入れ条件で確かめる（決定的な検査なので、通らない見込みである）。

ADR-027:67 の「axe は要件が生じるまで追加しない」は、この調査で要件が生じたので、E2E に入れる決定で改める。
ADR を足す作業は #647 に含める。
`test-strategy.md:62` の「component test と E2E test に組み込む」は、E2E だけに直す。

依存は `@axe-core/playwright` 4.14.0 の 1 つである（axe-core 4.14.0 を連れてくる）。

- ライセンスは MPL-2.0 で、用途の制限はない（ADR-098 の react-doctor の条項に当たるものはない）。npm の provenance（SLSA）が付いている。
- peer の `playwright-core` は、`@playwright/test` が持つ 1.63.0 に解決した。
- `vp add` は `pnpm-workspace.yaml` の catalog に足す。使うのは `apps/web` の 1 つだけなので、ADR-100 に従い `package.json` に版を直接書く（`@playwright/test` と同じ）。

### lint の `jsx-a11y` を `Link` と `Button` にも効かせる（#648）

`jsx-a11y` の規則は、素の `<a>` と `<button>` だけを見る。
`components` で名前を要素に対応づけないと、`@tanstack/react-router` の `Link` と `@frontend/ui/button` の `Button` は、中身が空でも報告されない。

Oxlint 1.93.0 で、`<Link to="/x" />`、`<Button onClick={…} />`、`<a href="/y" />`、`<button />` を含むファイルを調べた。

- `components` なし：素の `<a>` と `<button>` だけが報告された（`anchor-has-content`、`control-has-associated-label`）。
- `components: { Link: "a", Button: "button" }`：`Link` と `Button` も報告された。
- この設定だけでは、`anchor-is-valid` が `<Link to>` をすべて「`href` がない」と報告する（`apps/web/src` で 7 か所）。`["error", { specialLink: ["to"] }]` を渡すと通り、`<a href="#">` は報告され続けた。
- 2 つを足した設定で、`apps/web/src`、`apps/web/e2e`、`packages` の `vp lint` に `jsx-a11y` の報告は 0 件だった。`<Link to="/orders" />` と `<Button />` は報告された。

今のコードに違反はないので、この変更は将来の取りこぼしを止める。
`components` は名前の写像で、`packages/ui` に素の要素を包む部品を足しても自動では増えない。
足し方を `ui-and-style.md` の手順に書く（#648）。

## 取り込まないもの

- **Storybook と a11y addon、story 必須の検査**：#284 の結論を変えない。#284 の見直す条件は、共有の部品が複数になること、a11y の Issue が Storybook を選ぶこと、jsdom で再現できない不具合が出ることだった。この調査は a11y に別の経路（E2E）を選ぶので、2 つ目の条件は満たさない。`packages/ui/src` の部品は今も `button.tsx` 1 つだけである。Storybook 10.6.8 は、実行していない。向こうは Storybook 10.6.5 と Vite+ 1.6.0 で動かしている（ADR 0033:30 は、Vite+ に公式の保証がないと書く）。
- **`"todo"` から始める段階と警告の reporter**：上に書いた。reporter は、addon の非公開の構造を読む（ADR 0033:32）ため、Storybook を上げるたびに確かめる費用が付く。こちらは Storybook を入れないので、この費用も払わない。
- **jsdom での axe（`axe-core` を直接、または `jest-axe`、`vitest-axe`）**：次の理由で入れない。
  - 見つかるのは構造の規則だけで、`aria-valid-attr-value` のように E2E でも見つかる違反に限られる。`color-contrast` は `incomplete`、`target-size` は見逃す（#331 の fixture と今回の実画面）。
  - 同じ規則を 2 か所に置くと、タグと除外の設定が 2 つになる。
  - ラッパーは遅れている。`jest-axe` 11.0.0（2026-07-26）は axe-core 4.12.1 に固定し、`vitest-axe` 0.1.0 は 2025-01 から更新がない。
  - jsdom で流すと、`color-contrast` の判定のたびに `HTMLCanvasElement.getContext` の `Not implemented` が出る。1 つのテスト（描画と `axe.run`）は 0.76 秒から 1.18 秒かかった。
  - E2E が届かない状態（サーバーの失敗ごとの表示など）で構造の違反が出たら、その状態を `a11y.spec.ts` の画面に足す。1 画面の費用は 1 回の `analyze()` の 0.3 秒から 2.6 秒である。
- **Vitest の browser mode で component test を流す**：#331 の結論を変えない。#331 は「#335 が、レイアウトに依存する規則を component test で止めると決めたとき」を見直す条件にした。この調査は止める場所を E2E にしたので、条件を満たさない。Vitest 5.1.1 の browser mode の docs に axe を流す仕組みはなく、あるのは ARIA のツリーを比べる `toMatchAriaSnapshot` である（`docs/guide/browser/aria-snapshots.md`）。これは構造の回帰を見つけ、WCAG の違反は報告しない。
- **`anchor-ambiguous-text`（nursery）**：ADR-034 が nursery を有効にしない方針で、この調査の対象ではない。
- **Keycloak のログイン画面の検査**：こちらの画面ではない。テーマを変えるときは、`docker/keycloak` の変更として別に判断する。

## 限界

- Storybook と `@storybook/addon-vitest` は実行していない。向こうの設定と公式の文書を読んだ範囲で比べた。
- 流した画面は、シーダーの代表データと、その場で作った 1 件の注文である。画面の全部の状態（空の一覧、サーバーの 5xx、権限がない画面など）は流していない。
- 色の組の結果は、`prefers-color-scheme` に従う今の実装に依る。#343（ダークモード）が手動の切替を足すと、切り替え方が変わる。
- 320px の幅は、軽く確かめただけである。#337（スマホの幅）が決める確かめる幅に合わせて、組を足す。
- axe は WCAG の違反の一部しか見つけない（Storybook の文書は最大 57% とする）。keyboard 操作と screen reader の読み上げは、`test-strategy.md:51` と `:64` のとおり、手動で確かめる。
- `incomplete`（人が確かめる）の扱いは決めていない。#647 は、結果の全体を HTML report に添付するところまでを求める。
- CI の runner での所要時間は測っていない。開発機の `analyze()` の値だけである。
- 向こうの CI のログと、Storybook の story を流した結果は確かめていない。向こうの Issue、Pull Request、ADR、コードの記述に依る。
- axe-core 4.14.1（2026-11-16）が出ているが、pnpm は 4.14.0 を選んだ。理由は確かめていない。
- jsdom の axe は、トップ、検証エラー、下書きの詳細の 3 つだけを流した。404 は流していない。

## 確かめたこと

- npm（2026-11-23）：`@axe-core/playwright` 4.14.0（2026-10-15 のリリース、axe-core `~4.14.0` に依存、peer は `playwright-core >= 1.0.0`）、axe-core 4.14.1、`@playwright/test` 1.65.1（こちらは 1.63.0）、`jest-axe` 11.0.0、`vitest-axe` 0.1.0、Storybook 10.6.8、`@storybook/addon-vitest` 10.6.8（peer は vitest `^4.0.0 || ^5.0.0`）、`vite-plus` 1.7.0（こちらは 1.6.0）、oxlint 1.94.0（`vite-plus` 1.6.0 は 1.93.0 を連れてくる）、Vitest 5.1.1。
- `@axe-core/playwright` と axe-core のライセンスは MPL-2.0 で、どちらも npm の provenance（SLSA）が付いている。
- Playwright のリリースノートで `axe` を探すと、一致するのは 1.57 の `page.accessibility` の削除だけで、axe を使うよう案内している。1.58 から 1.65 に axe に当たる機能の追加はない。ARIA のスナップショットの機能は増えている。
- Oxlint の `jsx-a11y` は 37 規則で、eslint-plugin-jsx-a11y 6.10.2 の 40 規則から、非推奨の `accessible-emoji`、`label-has-for`、`no-onchange` を除いたものである。1.90.0 から 1.94.0 の間も、`jsx-a11y` の修正が続いている。
- `frontend/` は、`6a09662` と `ee8bf60` の間で差分がない。

## 出典

- [Playwright: Accessibility testing](https://playwright.dev/docs/accessibility-testing)（`makeAxeBuilder`、`withTags`、`testInfo.attach`）
- [Storybook: Accessibility tests](https://storybook.js.org/docs/writing-tests/accessibility-testing)（`parameters.a11y.test` の `"off"`、`"todo"`、`"error"`、CI の挙動、axe-core が最大 57% の WCAG の問題を見つけること）
- [axe-core のタグ](https://www.deque.com/axe/core-documentation/api-documentation/#axe-core-tags)
- [Vitest: ARIA snapshots](https://vitest.dev/guide/browser/aria-snapshots)、[Vitest: Component testing](https://vitest.dev/guide/browser/component-testing)
- [Playwright のリリースノート](https://playwright.dev/docs/release-notes)
- [Oxlint のリリース](https://github.com/oxc-project/oxc/releases)
- 向こう：`apps/web/.storybook/main.ts`、`preview.ts`、`apps/web/test/a11y-warnings-reporter.ts`、`docs/adr/0033-shadcn-base-ui-tailwind-v4-storybook.md`、`docs/adr/0039-e2e-playwright-gate-and-ai-for-authoring.md`、`tools/scripts/src/checks/repo-policy/ui-stories.ts`、`vite.config.ts`、`docs/web-frontend/testing.md`、Issue #315、#464、Pull Request #467、#474
- こちら：`frontend/vite.config.ts`、`frontend/apps/web/playwright.config.ts`、`frontend/apps/web/e2e/`、`docs/frontend/test-strategy.md`、`component-design.md`、`form-validation.md`、`dark-mode.md`、`docs/e2e/testing-strategy.md`、`docs/repository/branch-protection.md`、ADR-025、ADR-027、ADR-034、ADR-057、ADR-087、ADR-098、ADR-100、#277、#284、#331

## 作業の Issue

- #647：axe で画面のアクセシビリティの違反を止め、見つかった違反を直す
- #648：jsx-a11y の規則を Link と Button にも効かせる

## ほかの Issue との関係

- **#451**：E2E を `CI gate` の必須にする作業。#647 の検査が merge を止めるのは、#451 が入ってからである。
- **#336**：404 の画面を扱う。#647 は 404 に `<main>` を置くので、#336 の結論と作業の順に合わせる。
- **#337**：スマホの幅。#647 は、確かめる幅の組を 1 か所で足せる形にする。
- **#343**：ダークモード。手動の切替を入れるなら、#647 の配色の組の作り方を合わせる。
- **#284、#331**：結論を変えない。見直す条件は、E2E に axe を入れた後でも変わらない。

## 付録

### `a11y-probe.mjs`（調査用、commit しない）

`/verify-demo` の `drive.mjs` が呼ぶ journey の中核だけを示す。

```js
const AxeBuilder = require("@axe-core/playwright").default;
const TAGS = ["wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "wcag22aa", "best-practice"];

for (const [variant, scheme, size] of [
  ["light-1280", "light", { width: 1280, height: 900 }],
  ["dark-1280", "dark", { width: 1280, height: 900 }],
  ["light-320", "light", { width: 320, height: 640 }],
]) {
  await page.emulateMedia({ colorScheme: scheme });
  await page.setViewportSize(size);
  const result = await new AxeBuilder({ page }).withTags(TAGS).analyze();
  // result.violations と result.incomplete を画面と組ごとに記録する
}
```

### `a11y-jsdom-probe.test.tsx`（調査用、commit しない）

```tsx
/* @vitest-environment jsdom */
const result = await axe.run(document.body, { runOnly: TAGS });
// renderRoute("/orders/new") で空のまま送信した後の結果：
//   violations=[aria-valid-attr-value(2)] incomplete=[color-contrast]
```

### lint の設定の差分（調査用）

```diff
-    settings: { shadcn: { componentImports: ["^@frontend/ui(/|$)"] } },
+    settings: { shadcn: { componentImports: ["^@frontend/ui(/|$)"] }, "jsx-a11y": { components: { Link: "a", Button: "button" } } },
...
+      "jsx-a11y/anchor-is-valid": ["error", { specialLink: ["to"] }],
```
