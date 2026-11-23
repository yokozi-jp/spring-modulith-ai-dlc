# スマホの幅への対応を比べる（#337 の調査）

この文書は #337 の調査の記録で、merge しない。
結論は #337 のコメントと同じで、作業は #640 と #641 で行う。
向こうは `yokozi-jp/agent-software-factory`（`origin/main`、commit `3ba2640`）、こちらは `yokozi-jp/spring-modulith-ai-dlc`（`origin/main`、commit `ee8bf60`）である。
調べた日は 2026-11-23 である。

## 結論

一部採用とする。

- 採用：スマホの縦持ち、幅 320 CSS px から使えることを保証する。確認の幅は 320px と 390px にする。
- 採用：触って押す部品を 44×44 CSS px 以上にする。ただし、切り替えの条件は向こうの画面の幅（`md`）ではなく、入力装置の `pointer-coarse:` にする。
- 採用：幅の保証を Playwright の E2E の project（320px と 390px、`hasTouch: true`）で確かめる。
- 取り込まない：`md` の 1 か所だけに境目を絞る規則、Storybook の story と PR の必須チェック、リリース前の実機の確認を常設の手順にすること。

理由は 3 つある。

1. 320px は WCAG 2.2 SC 1.4.10 Reflow（AA）の値で、`component-design.md:54` が従うとする WCAG に入っている。
   一方、`screen-size-and-responsive.md:22` は、想定解像度を下回ったらスクロールバーで出すとしており、2 つの文書が食い違っている。
   320px は、幅 1280px の 400% 拡大と同じ幅なので、スマホを使わない利用者にも効く。
2. 今のこちらの画面は、幅 320px で 8 つの route のうち 2 つだけがあふれる（後の「確かめたこと」）。
   向こうが保証の範囲を 5 つの画面に絞ったのに対し、こちらは画面が 8 つしかなく、全画面を保証しても直す量が小さい。
   画面が増えるほど、後から直す量は増える。
3. 実ブラウザで確かめる層が、こちらでは E2E の Playwright（ADR-057）しかなく、すでに動いている。新しい依存は要らない。

想定解像度の値（`screen-size-and-responsive.md:17` は「論理解像度で一つに定める」とする）は、こちらの docs のどこにも書かれていない（`git grep` で `想定解像度` と `1366` を `docs/` から探すと、この文書の規則の文だけが当たる）。
この調査では決めない。

## 比べたもの

### 向こう

- `docs/web-frontend/mobile.md`（PR #730 で足した。全文）
- `docs/web-frontend/responsive.md`、`resolution.md`（フューチャー株式会社の原文を取り込んだもの）
- Issue #718（範囲を決める。解決のコメントと追記）、Issue #724（実施）、PR #730（実装。merge commit `27d8655`）、Issue #728（スマホの E2E。#453 の後）
- `packages/ui/src/components/button.tsx`、`apps/web/.storybook/preview.ts`、`apps/web/src/app.stories.tsx`

### こちら

- `docs/frontend/screen-size-and-responsive.md`、`component-design.md`、`ui-and-style.md`、`test-strategy.md`、`browser-support.md`
- `docs/e2e/testing-strategy.md`
- ADR-025、ADR-027、ADR-040、ADR-057
- `frontend/apps/web/index.html`、`playwright.config.ts`、`src/`、`frontend/packages/ui/src/button.tsx`

## 項目ごとの比較

| 向こうの規則                                                                           | 向こうの場所                              | こちらの扱い                                               | 判定                                |
| -------------------------------------------------------------------------------------- | ----------------------------------------- | ---------------------------------------------------------- | ----------------------------------- |
| viewport は `width=device-width, initial-scale=1.0`                                    | `resolution.md:59-78`                     | `index.html:6`、`screen-size-and-responsive.md:27-28`      | 同じ決定                            |
| CSS はモバイルファースト                                                               | `responsive.md:206-222`、`mobile.md:28`   | `screen-size-and-responsive.md:42-46`                      | 同じ決定                            |
| 縦持ちだけ保証する。横持ちは操作不能にならない程度                                     | `resolution.md:38-40`、`mobile.md:18`     | `screen-size-and-responsive.md:23` は縦向きを前提にする    | 同じ決定                            |
| 項目の多い画面は、減らす、同じ画面で切り替える、別画面、アプリの順                     | `responsive.md:236-242`                   | `screen-size-and-responsive.md:48-56`                      | 同じ決定                            |
| Tailwind の既定の prefix を使う                                                        | `responsive.md:130-140`                   | `screen-size-and-responsive.md:30-40`                      | 同じ決定                            |
| 押せる大きさは 24px 以上（SC 2.5.8）                                                   | `mobile.md:27`                            | `component-design.md:56` が書くが、検査がない              | 同じ決定。検査は #335               |
| 下限の幅は 320px（SC 1.4.10）                                                          | `mobile.md:19`                            | `screen-size-and-responsive.md:22` が逆の規則を書く        | 取り込む（#640）                    |
| 確認の幅は 360px と 390px                                                              | `mobile.md:20`                            | 決めていない                                               | 一部採用（320px と 390px。#640）    |
| 主な操作は `md` 未満で 44px（`h-11 md:h-8`）                                           | `button.tsx:23`、`mobile.md:28`           | `button.tsx:22-31` は 32px、28px、36px                     | 一部採用（`pointer-coarse:`。#641） |
| 切り替えの境目は `md` の 1 か所                                                        | `mobile.md:22`                            | サイドバーも `use-mobile.ts` もなく、`sm:` と `md:` を使う | 取り込まない                        |
| story を 320px と 390px で出し、横スクロール、axe、44px を PR の必須チェックで確かめる | `app.stories.tsx:711-778`、`mobile.md:37` | Storybook がない                                           | 取り込まない（E2E に置く。#640）    |
| E2E は 390px の 1 本を merge 後と定期だけ                                              | `mobile.md:38`、Issue #728                | E2E は必須でない check で、path filter 付き                | 一部採用（#640）                    |
| リリース前に実機で iPhone と Android を各 1 台                                         | `mobile.md:43-51`                         | リリースの確認の手順がない（`release-management.md`）      | 取り込まない                        |
| axe の `runOnly` に `wcag22aa` を足す                                                  | `preview.ts:26`                           | axe を使っていない（ADR-027:67）                           | #335 で扱う                         |

## 取り込むもの

### 幅 320px の保証（#640）

向こうは、スマホは縦持ちだけを保証し、下限は 320px の 1 つ、360px と 390px は見た目の確認の幅にする（`mobile.md:10`、Issue #718 の Q1 と Q2）。
下限の根拠は WCAG 2.2 SC 1.4.10 Reflow である。

W3C の Understanding は、この基準を「縦スクロールの内容を 320 CSS px の幅で、情報と機能を失わず、2 方向のスクロールなしで出す」と定める。
例外は、二次元の配置が要る部分で、地図、図、動画、データの表（個々のセルではなく表）、操作中に道具の列を見せ続ける必要がある画面が挙がる。
Note 1 は「320px は、幅 1280px の 400% 拡大と同じ」と書く。

こちらの `screen-size-and-responsive.md:22` は、想定解像度を下回ったらはみ出した部分をスクロールバーで出すとする。
これは、1.4.10 の例外に当たらない画面で、基準を満たさない。
向こうも同じ行を原文のまま残すが（`resolution.md:53,57`）、`mobile.md:19` が 320px の下限で範囲を区切っている。
こちらは、この行を直す。

対象の画面は、向こうと違って全画面にする。
向こうは、ログイン、トップ、メニュー、ユーザーメニュー、403 と 404 の 5 つに絞り、業務の画面は範囲外にした（Issue #724 の Out of Scope）。
こちらは route が 8 つで、業務の画面（注文、所属の管理）をすでに含む。
8 つのうち、幅 320px であふれたのは 2 つだった。

確認の幅は 390px を足す。
向こうが挙げる「360px は Android、390px は iPhone の多数派」は、まとめ記事の数字で、一次の統計ではないと向こう自身が書く（`mobile.md:20`）。
こちらも一次の統計を確かめられなかった。
そのため、数字の根拠は書かず、390px は「代表的な iPhone の幅」の確認として置き、360px は足さない。

### 触る部品を 44px にする（#641）

向こうは、Button の `default`、`lg`、`icon`、`icon-lg` と `SidebarMenuButton` の `default` を、`md` 未満で 44px にする（`h-11 md:h-8`、`button.tsx:23`、`mobile.md:28`）。
44px は WCAG 2.2 SC 2.5.5（AAA）の値で、アプリ全体で AAA を採るわけではないと向こうも書く。
W3C の SC 2.5.8 の Understanding は、24px を最小とし、「大切な操作には 2.5.5 の 44px を目指す」と勧める。

こちらは、`button.tsx:22-31` が 32px、`sm` が 28px である。
28px の `sm` は、所属の管理の「削除」（`members-table.tsx:65`）とログアウト（`app-shell.tsx:51`）に使い、削除は役割の `select`（32px）の隣に並ぶ。
shadcn の最新の base-nova の `button` と `input` は、こちらと同じ寸法で、タッチ向けの処理を持たない。

切り替えの条件は、向こうの画面の幅ではなく `pointer-coarse:` にする。
理由は次のとおりである。

- 向こうも、`pointer-coarse:` の方が「指で触るか」を正確に表すと書き、幅にしたのは story の環境が `pointer: fine` だからとする（`mobile.md:30`）。
  こちらの E2E は Playwright で、`hasTouch: true` を渡せる。
  Chromium で確かめると、`hasTouch: true` で `(pointer: coarse)` が true、渡さない場合は false だった。
- 幅の条件は、768px 以上のタブレットを拾えず、PC の狭い window を大きくする。
- Tailwind v4 の `pointer-coarse` variant は `@media (pointer: coarse)` を出す（Tailwind の hover、focus、その他の状態のドキュメント）。

44px は AAA の値で、要件ではない。
それでも取り込むのは、費用が `button.tsx` の 4 つの size と、`h-8` を持つ 3 つの生の `input` と `select` に限られ、削除の隣の押し間違いを減らせるためである。

### 実ブラウザで確かめる層（#640）

向こうは、story（Storybook）を 320px と 390px で出し、横スクロール、axe、44px を PR の必須チェックで確かめる（`app.stories.tsx:711-778`）。
E2E は 390px の 1 本を merge 後と定期だけにする（`mobile.md:38`）。

こちらは Storybook がなく、ADR-027 は「jsdom は layout と完全なブラウザ挙動を再現しない」を Negative に挙げ、Vitest の Browser Mode をすべての component test に使うことを、導入の重さから見送った。
横スクロールの有無と寸法は layout なので、jsdom では確かめられない。
実ブラウザの層は、すでにある Playwright の E2E だけである。

そのため、`playwright.config.ts` に `mobile-320` と `mobile-390` の project を足し、`mobile.spec.ts` が全 route で `documentElement.scrollWidth <= innerWidth` を確かめる。
向こうの Issue #728 と同じく、端末のエミュレーション（`devices`）は使わず、viewport の幅だけを変える。
`isMobile: true` は viewport の扱いを変える（`about:blank` で `innerWidth` が 980 になることを確かめた）。

E2E は必須でない check なので（`branch-protection.md:108`）、merge は止められない。
向こうは story を必須にしているため、止める強さは向こうより弱い。
この差は受け入れる。
Browser Mode を局所的に使う形は、依存の追加と ADR-027 の改訂が要り、#335 が決める axe の置き場所と合わせて決めるのがよい。

## 取り込まないもの

- **切り替えの境目を `md` の 1 か所にする規則**（`mobile.md:22`）：向こうは `use-mobile.ts` のサイドバーと CSS の `md:` を同じ値に揃えるために置いた。
  こちらにはサイドバーも `use-mobile.ts` もなく、`sm:`（余白）と `md:`（2 列）を使っている。
  `screen-size-and-responsive.md:30-40` は、breakpoint を layout を変える箇所だけに置くとすでに定めている。
- **Storybook の story と PR の必須チェック**：Storybook を入れる理由が、スマホの幅だけでは足りない。
- **リリース前の実機の確認を常設の手順にする**（`mobile.md:43-51`）：こちらにはリリース前の確認の手順がない。
  iOS の Safari は、E2E の対象にもならない（WebKit は #158 で未検証）ので、実機で確かめる価値はある。
  常設の手順にはせず、#640 の Pull Request で、確かめた範囲を Evidence に書く。
- **`SidebarMenuButton` の寸法**：こちらにサイドバーがない。
- **`Sheet` と `Dialog` の閉じるボタンの寸法**：こちらにこれらの部品がない。

## #335 との境界

チケット #335（アクセシビリティを検査で止める）は別のエージェントが並行で調べている。
決定は書かず、重なる点だけを挙げる。

| 点                                | この調査（#337）が決めること                                                                                             | #335 が決めること                                             |
| --------------------------------- | ------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------- |
| 横スクロール（SC 1.4.10）         | `scrollWidth` を `mobile-*` project で確かめる。axe 4.14.1 の規則（105）に Reflow の規則はなく、axe では代わりにならない | なし                                                          |
| 24px の `target-size`（SC 2.5.8） | なし。`component-design.md:56` の文は変えない                                                                            | axe の `wcag22aa` を流すか、流す場所                          |
| 44px（SC 2.5.5）                  | `mobile.spec.ts` の寸法の確認。axe の規則ではない                                                                        | なし                                                          |
| 実ブラウザの層                    | 今ある Playwright の E2E を使う                                                                                          | axe を流す場所（component test、E2E、Vitest の Browser Mode） |
| `overflow-x-auto` の表            | 折り返しを先に試す。スクロールの領域にするなら、キーボードで操作できるようにする                                         | `scrollable-region-focusable`（`wcag2a`）の扱い               |

axe の `target-size` と `color-contrast` は layout を使うので、jsdom では当てにならない。
もし #335 が E2E に axe を足す形なら、`mobile.spec.ts` の各 route で同じ検査を呼ぶ。Browser Mode を選ぶなら、幅の確認もそちらへ移せる。

調べたついでに、実ブラウザの axe 4.14.1（`wcag2a`、`wcag2aa`、`wcag21a`、`wcag21aa`、`wcag22aa`、`best-practice`）が出した違反は次のとおりである（API は固定の JSON、幅 320px、390px、1280px で同じ）。
これは #335 の入力になる。この調査では直さない。

- `color-contrast`：`/`（1 件）、`/orders/<id>`（2 件）、`/members`（2 件）
- `landmark-one-main`、`region`：404 の画面（各 1 件）
- `target-size`：どの画面も 0 件

## 限界

- 実測は、`vp dev` の開発サーバーと Chromium だけで行った。API は `page.route` で固定の JSON を返した（実際の backend とシーダーのデータではない）。`vp build` した画面と WebKit（iOS の Safari）では確かめていない。
- 注文の作成の `select` のあふれは、日本語で 25 字ほどの商品名を置いたときだけ出た。シーダーの商品名（Datafaker の値）の長さは確かめていない。
- iOS の Safari は、`font-size` が 16px 未満の入力にフォーカスすると拡大する、という挙動が知られている。こちらの `input` は `text-sm` 相当である。shadcn の base-nova の `input` は `text-base md:text-sm` で、この対策に当たる形だが、iOS 実機では確かめていない。
- 360px と 390px が多数派という数字、日本での端末の幅の分布は確かめていない。
- 44px の根拠（SC 2.5.5）は AAA で、要件ではない。取り込む判断は費用と押し間違いの減少による。
- E2E の `mobile-*` project は必須でない check で動く。merge は止められない。
- Vitest の Browser Mode は、Vite+ に取り込みの記述がある（`vite-plus/docs/guide/vitest-v5.md`）が、provider の依存がなく、こちらでは動かしていない。

## 確かめたこと

- 向こうの `docs/web-frontend/mobile.md`（51 行）、`responsive.md`、`resolution.md` を `origin/main`（`3ba2640`）から読んだ。`mobile.md` は PR #730（merge commit `27d8655`、2026-11-12）で足された。
- PR #730 の差分で、`button.tsx` の `default` と `lg` が `h-11 ... md:h-8`、`md:h-9`、`icon` が `size-11 md:size-8` になり、`preview.ts` の `runOnly` に `wcag22aa` が足されたことを読んだ。
- Issue #718 の決定（Q1 から Q8）と追記（44px を部品の既定にした）、Issue #724、#728 を読んだ。#728 は #453 に blocked で、open である。
- 画面の実測（Chromium、`vp dev`、`page.route` で `/api/*` を固定の JSON で置き換え、`bypassCSP: true` で axe を注入）。幅 320px、390px、768px、1280px で、8 つの route（`/`、`/tenants`、`/orders`、`/orders/new`、`/orders/<id>`、`/members`、`/logged-out`、存在しない URL）を開いた。
  - 幅 320px で、`documentElement.scrollWidth` が `innerWidth` を超えたのは `/`（342）と `/members`（588）だけだった。390px では `/members`（588）だけで、768px 以上ではなかった。
  - `/members` のあふれは、表（`members-table.tsx:20-65`）が UUID、`select`、名前に UUID を含む削除のボタンを 1 行に並べるためである。
  - `/` のあふれは、Vite の初期画面の説明文（`routes/_authenticated/_tenant/index.tsx`）である。
  - 24px 未満の操作要素（文の中の link を除く）は、320px では 0 件、390px 以上では「所属を管理する」の link（高さ 20px）の 1 件だった。axe の `target-size` はこれを違反にしなかった（近くに別の操作要素がなく、SC 2.5.8 の Spacing の例外に当たる）。
  - 44px 未満の操作要素は、どの幅でも各画面に 1 から 8 件あった（ログアウトの 28px、`select` と `input` の 32px など）。
- Playwright 1.63.0 の Chromium で、`pointer: coarse` は、`hasTouch: true` で true、PC の既定で false だった。`isMobile: true` では `about:blank` の `innerWidth` が 980 だった。
- axe-core 4.14.1 の規則 105 件に、Reflow（`wcag1410`）の規則はない。`target-size` は `wcag22aa`（`wcag258`）、`color-contrast` は `wcag2aa` に付く。
- W3C の Understanding（SC 1.4.10 Reflow、SC 2.5.8 Target Size (Minimum)）を読んだ。Reflow の Note 1 と例外、Target Size の Spacing と Inline の例外、2.5.5 を勧める記述を確かめた。
- 最新版は、Playwright 1.65.1（こちらは 1.63.0）、Tailwind CSS 4.3.5（こちらは 4.3.3）、axe-core 4.14.1、shadcn 4.23.0（こちらと同じ）である。Playwright と Tailwind CSS のリリースノートは読んでいない。この調査が頼る `hasTouch` と `pointer-coarse` は、こちらの版（Playwright 1.63.0）の実行と、現行のドキュメントで確かめた。
- shadcn の registry の base-nova の `button`（`size` の `default` が `h-8`、`sm` が `h-7`、`icon` が `size-8`）と `input`（`h-8`、`text-base md:text-sm`）を読んだ。
- E2E の `webServer` は `vp preview --mode test`、project は `setup` と `chromium`（`Desktop Chrome`）だけである（`playwright.config.ts:21-28`）。
- こちらの `frontend/packages/ui/src` は `button.tsx` だけで、`Sheet`、`Dialog`、`Sidebar` はない。

## 出典

- [W3C: Understanding SC 1.4.10 Reflow](https://www.w3.org/WAI/WCAG22/Understanding/reflow.html)
- [W3C: Understanding SC 2.5.8 Target Size (Minimum)](https://www.w3.org/WAI/WCAG22/Understanding/target-size-minimum.html)
- [Tailwind CSS: Hover, focus, and other states（pointer and any-pointer）](https://tailwindcss.com/docs/hover-focus-and-other-states)
- [axe-core 4.14.1 の規則の一覧](https://github.com/dequelabs/axe-core/blob/v4.14.1/doc/rule-descriptions.md)（`axe.getRules()` で確かめた）
- [Playwright: Emulation（hasTouch、isMobile）](https://playwright.dev/docs/emulation)
- 向こう：`docs/web-frontend/mobile.md`、`responsive.md`、`resolution.md`、PR #730、Issue #718、#724、#728、`packages/ui/src/components/button.tsx`、`apps/web/.storybook/preview.ts`、`apps/web/src/app.stories.tsx`
- こちら：`docs/frontend/screen-size-and-responsive.md`、`component-design.md`、`test-strategy.md`、`docs/e2e/testing-strategy.md`、`docs/repository/branch-protection.md`、ADR-027、ADR-057、`frontend/apps/web/playwright.config.ts`、`frontend/packages/ui/src/button.tsx`

## 作業の Issue

- #640：幅 320px から横スクロールなしで使えるようにし、E2E で止める。
- #641：タッチ操作で押す部品を `pointer-coarse:` で 44px 以上にする。#640 の後に行う。
