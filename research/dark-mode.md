# ダークモードを比べる（#343 の調査）

この文書は #343 の調査の記録で、merge しない。
結論は #343 のコメントと同じで、作業は #663 で行う。
向こうは `yokozi-jp/agent-software-factory`（`origin/main`、commit `3ba2640`）、こちらは `yokozi-jp/spring-modulith-ai-dlc`（`origin/main`、commit `a24fcf3`）である。
調べた日は 2026-11-23 である。

## 結論

一部採用とする。

- 採用：ライト、ダーク、システムの 3 択の手動切替を足す。初期値は OS の設定で、利用者が選んだ値を OS の設定より優先し、localStorage に保存する。配色は `<html>` の `dark` class で切り替える。
- 採用：保存した選択は、`index.html` の `<head>` に置く同一オリジンの小さな classic script（`/theme-init.js`）で、最初の描画の前に当てる。CSP は変えない。
- 取り込まない：向こうの当て方（`main.tsx` が `createRoot` の前に `dark` class を付ける）。実ブラウザで、最初の描画のあとに色が変わった。
- 取り込まない：inline script と CSP の hash、Cookie と配信点での HTML の書き換え、shadcn の `ThemeProvider`、next-themes、`light-dark()` による token の定義、`blocking="render"`。

理由は 4 つある。

1. こちらはすでに OS の設定に従うダークを持つが（`style.css` の `@media (prefers-color-scheme: dark)`）、手動の切替がない。
   `docs/frontend/dark-mode.md:20-28` は、切替を加えるときの形（OS を初期値、選んだ値を優先、localStorage、class に揃える）をすでに定める。
   向こうの `docs/web-frontend/dark-mode.md` と `theme.ts` は同じ形で、決定は同じである。
   差は「当てる時機」だけである。
2. 向こうの当て方は、`theme.ts:1-3` と `main.tsx:50` のコメントが言う「最初の描画の前に反映する（ちらつき防止）」を満たさない。
   向こうの build を実ブラウザ（Chromium）で開くと、遅延なしでも間違った色の frame が出た。
   こちらの `docs/frontend/dark-mode.md:27`「選んだ値は bundle した script で最初の描画より前に適用する」も、同じ理由で満たせない。
3. `<head>` の同一オリジンの classic script は、`script-src 'self'` の中にあり、CSP を変えない。
   こちらの build（実際の CSP の header）に置くと、遅延を足した 5 回ずつの実測で、間違った色の frame は 0 回だった。
   inline script は CSP に止められ、選択が当たらなかった。
4. `<head>` の script は、最初の描画を待たせる費用がある。
   script が CSS より遅いとき、最初の描画は script の応答を待つ（中央値で、今の 147 ms が 307 ms になった）。
   この費用は、手動の切替の利得（OS の設定を変えられない利用者が選べる）に見合うと判断した。
   利用者の要望は、リポジトリの Issue に見当たらない（#343 と #647 だけが「ダーク」を含む）。判断は、docs の規約（ハイブリッド型）と費用による。

## #647（E2E に axe を入れる作業）との関係

チケット #335 は、ライトとダークの画面に axe を流すと決めた。作業の Issue は #647 である。
作業の Issue #647 は「ダークは `prefers-color-scheme` に従う」と書き、`page.emulateMedia({ colorScheme })` で切り替える。
この前提は、今の実装（media query）では成り立つ。この調査の採用後も、次の条件で成り立つ（`change` の監視は、向こうの `applyStoredTheme` と同じ形を `main.tsx` に置いて測った）。

| 呼び方                                          | 今（media query） | `theme-init.js` のみ（class） | `theme-init.js` と bundle 側の `change` 監視（class） |
| ----------------------------------------------- | ----------------- | ----------------------------- | ----------------------------------------------------- |
| context の `colorScheme` を `goto` の前に与える | ダークになる      | ダークになる                  | ダークになる                                          |
| `page.emulateMedia` を `goto` の後に呼ぶ        | ダークになる      | **ライトのまま**              | ダークになる                                          |

- `page.emulateMedia` を `goto` の後に呼ぶと、`<html>` の class は変わらず、背景はライトのままだった（`theme-init.js` は読み込みのときに一度しか走らない）。
  `change` の監視を bundle 側に置くと追従する。
  監視がないとき、`a11y.spec.ts` のダークの組は、エラーなしでライトを 2 回検査する。
- 作業の Issue #663 は、bundle 側に `change` の監視を置く。#647 の `a11y.spec.ts` には、`emulateMedia` を `goto` の前に呼ぶことと、axe の前に `<html>` の class を確かめることを、#663 の受け入れ条件で求めた。
- ライトとダークの配色は変えない。`/logged-out` と 404 の画面（8 要素と 7 要素）で、media query 版の OS がダークのときと、class 版の「OS がライト、保存がダーク」のときの computed style（`color`、`background-color`、`border-color`、`outline-color`、`color-scheme`）が一致した。逆の組（ライト）も一致した。#335 が見つけた `color-contrast` の 2 件がダークで出ないことは、token の値が同じなので変わらないはずだが、認証後の画面では確かめていない。
- #647 の「やること」の `docs/frontend/dark-mode.md:19`「contrast を確認する」は、`a24fcf3` では `:17` である。#663 が同じファイルを変えるので、どちらが先でも行番号でなく文で合わせる。
- 手動の選択（保存した値）が OS の設定より優先されることは、axe の組ではなく、#663 の E2E で確かめる。#647 の組は「ライト」と「ダーク」の 2 つのままでよい。

## 比べたもの

### 向こう

- `apps/web/src/lib/theme.ts`（`applyTheme`、`applyStoredTheme`、`setThemePreference`）、`theme.test.ts`
- `apps/web/src/main.tsx:50`（`createRoot` の前の `applyStoredTheme`）
- `apps/web/src/components/user-menu.tsx`（3 つの `Button` と `aria-pressed`）
- `packages/ui/src/styles/globals.css`（`@custom-variant dark (&:is(.dark *))`、`.dark`）
- `docs/web-frontend/dark-mode.md`（原文のフューチャー株式会社のガイドラインを取り込んだもの）
- `apps/web/index.html`（inline script なし）

### こちら

- `frontend/packages/ui/src/style.css`、`button.tsx`（`dark:` を使う唯一の部品）
- `frontend/apps/web/vite.config.ts`（CSP、`preview` と `server` の header）、`index.html`、`public/`
- `docs/frontend/dark-mode.md`、`browser-storage-and-cache.md`、`browser-support.md`、`build-and-delivery.md`
- ADR-014（SPA を同一オリジンで公開する。CSP）、ADR-025（shadcn と Tailwind）、ADR-030（style 要素の許可）、ADR-068（CSP の報告）
- #335 の結論と #647 の本文

本番の CSP を配る側は、まだ決まっていない（ADR-014 の Context「本番の SPA 配信先とリバースプロキシも決まっていない」、`build-and-delivery.md` は条件だけを定める）。
バックエンドは SPA を配信せず（ADR-056）、CSP も付けない。CSP の正本は `frontend/apps/web/vite.config.ts` の `contentSecurityPolicy` で、`server` と `preview` が同じ header を返す。

## 項目ごとの比較

| 項目                     | 向こう                                                     | こちら                                                 | 判定                                          |
| ------------------------ | ---------------------------------------------------------- | ------------------------------------------------------ | --------------------------------------------- |
| 選択肢                   | ライト、ダーク、システムの 3 択。既定はシステム            | OS に従うだけ。docs は「切替を加えるなら OS を初期値」 | 取り込む（#663）                              |
| 優先順位                 | 明示した選択を OS より優先（`theme.ts:39-45`、テストあり） | `dark-mode.md:23` が同じ規則を定める                   | 同じ決定                                      |
| 保存先                   | localStorage（キー `theme`）。読めなければ `system`        | `dark-mode.md:24` は localStorage、使えなければ memory | 同じ決定（memory の fallback はこちらの規約） |
| 配色の定義               | `:root` と `.dark` の token                                | `:root` と media query の token                        | 取り込む（`.dark` へ移す）                    |
| `dark` variant           | `@custom-variant dark (&:is(.dark *))`                     | 既定の media query                                     | 取り込む（Tailwind の docs の形。後述）       |
| 当てる時機               | `main.tsx` が `createRoot` の前に呼ぶ                      | `dark-mode.md:27` は「bundle した script」             | **取り込まない**。`<head>` の classic script  |
| inline script            | 使わない（CSP）                                            | 使わない（ADR-014）                                    | 同じ決定                                      |
| 切替の UI                | `user-menu.tsx` の 3 つの `Button`（`aria-pressed`）       | なし                                                   | 取り込む（`app-shell.tsx` の header）         |
| サーバー側の保存、Cookie | なし                                                       | `dark-mode.md:25-26` は要件があるときだけ              | 取り込まない（要件なし）                      |

## 実ブラウザで確かめたこと

### 方法

- 画面は、認証なしで開ける `/logged-out` を使った（API は要らない）。
- build は `vp build`、配信は `vp preview` で、`vite.config.ts` の `preview.headers` がそのまま CSP を付ける。`curl -I` で、`script-src 'self'` を含む CSP の header を確かめた。
- Chromium は Playwright 1.63.0 の 153.0.8010.12（headless）。
- 描画された色は、CDP の `Page.startScreencast`（PNG、`everyNthFrame: 1`）で frame を採り、左上の画素の明るさで「ライト」か「ダーク」を決めた。前の文書の frame と区別するため、開始前の `about:blank` を赤にし、赤の frame は捨てた。
- ネットワークは、`page.route` で `.css`、`theme-init.js`、その他の `.js` の応答を種類ごとに遅らせた。
  - 同じ往復：CSS が 100 ms、`theme-init.js` が 100 ms、bundle（642 kB、gzip で 199 kB）が 500 ms。
  - script だけ遅い：CSS が 30 ms（cache から）、`theme-init.js` が 250 ms（再検証の往復）、bundle が 600 ms。
- 「ちらつき」は、期待する色と違う frame が 1 つでも出たこととした。
- 保存した選択は、Playwright の `storageState` の `origins[].localStorage` で、ページの script より前に与えた。OS の設定は context の `colorScheme` で与えた。
- この方法は headless の screencast なので、最初の描画より前の frame（白い canvas）は採れない。測れたのは、最初に描画された frame の色である。

測定の中心は、次のとおりである。

```js
await page.goto("about:blank");
await page.evaluate(() => (document.documentElement.style.background = "#f00"));
const cdp = await ctx.newCDPSession(page);
cdp.on("Page.screencastFrame", (f) => {
  frames.push({ ts: f.metadata.timestamp * 1000, tone: tone(f.data) }); // PNG の左上の画素
  cdp.send("Page.screencastFrameAck", { sessionId: f.sessionId });
});
await cdp.send("Page.startScreencast", { format: "png", maxWidth: 100, maxHeight: 75, everyNthFrame: 1 });
await page.route(/\.(js|css)(\?.*)?$/, async (route) => {
  await sleep(delayFor(route.request().url())); // css、theme-init.js、bundle で別の遅延
  await route.continue();
});
await page.goto(url, { waitUntil: "load" });
```

### 方式ごとの比較（5 回ずつ）

試した変更は、`main.tsx` の `createRoot` の前に向こうの `applyStoredTheme` を呼ぶ形（`main.tsx`）と、`index.html` の `<head>` に次の script を置く形である。
どちらも、`style.css` の token を `.dark` に移し、`@custom-variant dark (&:where(.dark, .dark *))` を足した build で試した。

```js
(function () {
  var p;
  try { p = localStorage.getItem("theme"); } catch (e) {}
  var dark = p === "dark" || (p !== "light" && matchMedia("(prefers-color-scheme: dark)").matches);
  document.documentElement.classList.toggle("dark", dark);
})();
```

表の「ちらつき」は、期待と違う frame が出た回数である。2 つの組は、「OS がライト、保存がダーク」と「OS がダーク、保存なし」で、同じ回数だったときは 1 つにまとめた。

| 方式                                  | 同じ往復：ちらつき | script だけ遅い：ちらつき | script だけ遅い：最初の描画（中央値） | 間違った色が見えた時間（中央値）             |
| ------------------------------------- | ------------------ | ------------------------- | ------------------------------------- | -------------------------------------------- |
| `main.tsx` で適用（向こうの形）       | 5 回中 5 回        | 5 回中 5 回               | 121 ms と 116 ms                      | 566 から 578 ms（同じ往復）、759 から 885 ms |
| `<head>` の classic script（同期）    | 0 回               | 0 回                      | 304 ms と 279 ms                      | なし                                         |
| `<head>` の classic script に `defer` | 2 回と 2 回        | 5 回中 5 回               | 60 ms と 58 ms                        | 約 220 ms（script だけ遅い）                 |
| `<head>` の `type="module"`           | 3 回と 1 回        | 5 回中 5 回               | 57 ms と 60 ms                        | 約 219 ms（script だけ遅い）                 |
| `type="module" blocking="render"`     | 0 回               | 0 回                      | 277 ms                                | なし                                         |
| inline script と CSP の `sha256`      | 0 回               | 0 回                      | 57 ms と 59 ms                        | なし                                         |

- inline script を CSP が今のままの build に置くと、`script-src-elem` の違反が報告され、`dark` class は付かなかった（保存がダークでもライトのまま）。
- 向こうの形は、`dark` class が bundle の実行まで付かない。class だけで配色を決めるので、OS がダークで保存がなくても最初の frame はライトになった。
- classic script の同期は、どの組でも 0 回だった。最初の描画は、script の応答まで待たされる（script だけ遅いとき、約 280 から 300 ms）。
- `defer` と `module` は、script が CSS より遅いときに 5 回中 5 回ちらついた。同じ往復でも一部の回でちらついたので、結果は CSS との競争で決まる。
- `blocking="render"` と、inline script と `sha256` は、ちらつかなかった。前者は Baseline の Limited availability である（後述）。後者は、script の内容が変わるたびに CSP の hash と配信の設定を合わせる必要があり、ADR-030 が style で退けた形と同じ費用になる。

向こうの build（`origin/main` の `3ba2640`、`apps/web` を `vp build`、`vp preview`、`/login`、3 回ずつ）も同じ方法で測った。

| 条件                                          | OS がライト、保存がダーク                | OS がダーク、保存なし | OS がダーク、保存がライト | OS がライト、保存なし |
| --------------------------------------------- | ---------------------------------------- | --------------------- | ------------------------- | --------------------- |
| 遅延なし：ちらつき                            | 3 回中 1 回                              | 3 回中 3 回           | 0 回                      | 0 回                  |
| CSS が 100 ms、JS が 500 ms：ちらつき         | 3 回中 3 回                              | 3 回中 3 回           | 0 回                      | 0 回                  |
| CSS が 100 ms、JS が 500 ms：間違った色の時間 | 約 440 ms（`light@125` から `dark@566`） | 約 440 ms             | なし                      | なし                  |

向こうの「ちらつき防止」のコメントは、この条件では当たらない。
最初の frame がライトなので、期待がライトのとき（保存がライト、または OS がライトで保存なし）は、ちらつきが出ない。ちらつくのは、期待がダークの利用者（ダークを保存した人と、OS がダークで保存がない人）である。

### 費用：最初の描画の時間（9 回、OS がライト、保存なし、中央値と範囲）

| 条件                            | 今（media query のみ） | `main.tsx` で適用      | `<head>` の classic script |
| ------------------------------- | ---------------------- | ---------------------- | -------------------------- |
| 遅延なし                        | 37 ms（24 から 54）    | 25 ms（20 から 40）    | 45 ms（38 から 74）        |
| 同じ往復（100 ms、500 ms）      | 151 ms（137 から 184） | 134 ms（127 から 151） | 172 ms（130 から 263）     |
| script だけ遅い（30、250、600） | 147 ms（67 から 272）  | 125 ms（67 から 233）  | 307 ms（282 から 340）     |

- script が CSS と同じ往復なら、最初の描画の増加は小さい（中央値で 8 から 21 ms、範囲は重なる）。測定では、ブラウザは CSS と script を並べて取り、最初の描画は遅い方に合わせて起きた。
- script が CSS より遅いと、最初の描画は script の応答まで遅れる。`/theme-init.js` は content hash を持たないので、`Cache-Control: no-cache` で返す（`index.html` と同じ）と、毎回の読み込みで再検証の往復が並ぶ。作業の Issue は、この条件を `build-and-delivery.md` に書く。
- 遅延の数値は `page.route` で作った値で、実際のネットワークの分布ではない。方式の差の向きを見るために使い、絶対値は信用しない。

### 決定的に確かめる方法

screencast は遅延と時間に頼るので、E2E には向かない。
代わりに、bundle の応答を止めたまま `body` の背景を読むと、決定的に確かめられる。

```js
await page.route(/\/assets\/index-.*\.js$/, async (route) => { await gate; await route.continue(); });
await page.goto(url, { waitUntil: "commit" });
await page.waitForTimeout(700);
// #root は空（React が描画する前）
getComputedStyle(document.body).backgroundColor; // ダークなら oklch(0.145 0 0)
```

| 形                         | OS がライト、保存がダーク    | OS がダーク、保存なし        |
| -------------------------- | ---------------------------- | ---------------------------- |
| `main.tsx` で適用          | `oklch(1 0 0)`（ライト）     | `oklch(1 0 0)`（ライト）     |
| `<head>` の classic script | `oklch(0.145 0 0)`（ダーク） | `oklch(0.145 0 0)`（ダーク） |

作業の Issue #663 は、この確認を E2E にする。

### CSP の下での他の確認

- `style-src-attr 'none'` の下で、`el.style.colorScheme = "dark"`（CSSOM）は効き、`setAttribute("style", "color-scheme: dark")` は効かず、`style-src-attr` の違反が 1 件報告された。class で切り替えるので使わないが、CSSOM の経由は CSP に止められない。
- 開発サーバー（`vp dev`、nonce 付きの CSP）でも、`public/theme-init.js` は CSP の違反なしに走り、2 つの組（OS がライトで保存がダーク、OS がダークで保存なし）で `dark` class が付いた。開発では CSS が JS で入るので、別の理由で最初の描画は遅れる。
- 今の `origin/main` の build を開くだけで、`script-src eval` の違反が 1 件報告される（`/assets/index-*.js` の 10 行目）。この調査の変更と関係なく、原因は調べていない。ADR-068 の CSP の報告にも出るかは確かめていない。

## Tailwind CSS と shadcn の現行のドキュメント

- Tailwind CSS（最新は 4.3.5、こちらは 4.3.3。2026-11-06）の dark mode のドキュメント（`tailwindcss.com/docs/dark-mode`）は、次を書く。
  - 既定の `dark` variant は `prefers-color-scheme` を使う。
  - 手動で切り替えるときは `@custom-variant dark (&:where(.dark, .dark *));` に上書きする。
  - 3 択（ライト、ダーク、システム）の例は `localStorage.theme` と `matchMedia` で `<html>` の `dark` class を決め、コメントに「ちらつきを避けるため、`head` の中に inline で足すのがよい（add inline in `head` to avoid FOUC）」と書く。
  - inline にできない（CSP）ときの代わりが、`head` の同期の外部 script である。
- Tailwind CSS の `CHANGELOG.md` で、4.2.0 から 4.3.5 の項目に、dark mode と `color-scheme` に関わる変更は見当たらなかった。こちらの版の 4.3.3 から上げる理由はない。
- shadcn の theming のドキュメントは、dark mode を「同じ token を `.dark` の selector で上書きする」とし、既定の CSS に `@custom-variant dark (&:is(.dark *));` を置く。
  - Tailwind の docs の形は `:where(.dark, .dark *)` で、`.dark` を付けた要素自身にも効き、詳細度が 0 になる。作業の Issue は Tailwind の形を使う。shadcn の CLI が足す部品は `dark:` を使うだけで、variant の定義には依存しない。
- shadcn の dark mode の Vite の手順（`ui.shadcn.com/docs/dark-mode/vite`）は、`ThemeProvider`（React の context）が `useEffect` の中で `<html>` の class を付ける。
  - React が描画したあとに走るので、`main.tsx` で適用する形（上の測定）よりさらに遅い。実行はしていない。
  - 保存先は localStorage（キー `vite-ui-theme`）で、`try/catch` と memory の fallback がない。こちらの規約（`browser-storage-and-cache.md`）に合わない。
- next-themes（0.4.6）は、`<script dangerouslySetInnerHTML>` の inline script を出し、CSP のために `nonce` を渡せる（README の `nonce`）。
  - こちらの CSP は `script-src 'self'` で、静的配信は request ごとの nonce を埋め込めない（ADR-030）。inline の hash は上の形と同じ費用になる。実行はしていない。
- shadcn の変更履歴（`ui.shadcn.com/docs/changelog`）は、最新の項目（2026 年 10 月、Trace と Code Block）だけを読んだ。dark mode に関わる項目はなかった。

## 取り込まないもの

- **`main.tsx` で当てる形**：上の測定で、ちらつく。
- **inline script と CSP の `sha256`**：ちらつかず、script だけが遅い条件の最初の描画も早い（57 ms と、classic script の 304 ms）。ただし、script の内容が変わるたびに、build が出す HTML の hash と配信点の CSP の header を合わせる必要がある。本番の配信点が決まっておらず、CSP の header を build の出力から作れるか分からない。ADR-030 が style の hash を退けた理由（配信の設定との同期）と同じ費用が、script にも掛かる。
- **Cookie と配信点での HTML の書き換え**：配信点が Cookie を読んで `<html class="dark">` を出すと、script なしでちらつかなかった（実行した。n=1）。ただし、静的配信と合わず（`build-and-delivery.md` は CDN と object storage を基本にする）、Cookie が全 request に付く。`dark-mode.md:26` が「サーバーが配色ごとに応答を変える必要があるとき」だけ Cookie を使うと定め、その要件がない。
- **shadcn の `ThemeProvider`**：上のとおり、遅く、保存の失敗に備えていない。
- **next-themes**：inline script と nonce を前提にする。
- **`light-dark()` による token の定義**：`light-dark()` は Widely available（2024-05-13 から。web-features 3.47.0）で、`color-scheme` を使えば JS なしで OS に従える。ただし、手動の選択は結局 `<html>` の属性を書く script が要る。shadcn の標準の形（`:root` と `.dark`）から外れ、`shadcn add` が足す token の定義と食い違う。
- **`blocking="render"`**：`<script type="module" blocking="render">` はちらつかなかったが、web-features 3.47.0 で Baseline の Limited availability（Chrome 105、Edge 105、Safari 18.2。Firefox は未対応）である。`browser-support.md` が本番のコードで使わないと定める。
- **`<meta name="color-scheme">`**：効果を測れなかった（後述）。`:root` と `.dark` の `color-scheme` で足りる。

## 限界

- 色は headless の screencast で採った。最初の描画より前の frame（白い canvas、別オリジンからの遷移でログインから戻るときの白）は採れず、`<meta name="color-scheme" content="light dark">` の効果は、今の build と classic script の build、OS がライトとダークの 4 つの条件で、差も出なかった（CSS を 700 ms 遅らせても、最初の frame は最初の描画だった）。ログイン後の戻りの白い一瞬は、確かめていない。
- 遅延は `page.route` の値で、実際の回線の分布ではない。5 回と 9 回の中央値で、統計的な検定はしていない。
- 画面は `/logged-out` と 404 だけで確かめた。認証後の画面と、Base UI の popup（portal）の見た目は確かめていない。
- Chromium だけで確かめた。Firefox と WebKit（iOS の Safari）、実機では確かめていない。
- 向こうの build は、`/login` を遅延なしと 100 ms と 500 ms の 2 条件、3 回ずつで測った。
- 本番の配信点で `/theme-init.js` を `Cache-Control: no-cache` で返せるかは、配信点が決まっていないので確かめられない。
- `theme-init.js` と bundle 側の適用の 2 か所に規則の写しが残る。#663 は、2 つが同じ結果になることを 1 つの表で確かめる。
- shadcn の `ThemeProvider` と next-themes は、ドキュメントとソースを読んだだけで、実行していない。
- Tailwind CSS のリリースノートは `CHANGELOG.md` の 4.2.0 から 4.3.5 を `dark`、`color-scheme` で grep した。shadcn の変更履歴は最新の項目だけを読んだ。
- #647 の `a11y.spec.ts` は未着手で、実際の spec では動かしていない。`emulateMedia` の挙動は、`/logged-out` と 4 つの build で確かめた。
- 手動の切替を求める利用者の数と、業務 PC で OS のテーマを変えられない割合は確かめていない。

## 確かめたこと

- 向こうの `apps/web/src/lib/theme.ts`、`theme.test.ts`、`main.tsx`、`index.html`、`docs/web-frontend/dark-mode.md`、`packages/ui/src/styles/globals.css`（`.dark` と `@custom-variant dark`）、`components/user-menu.tsx` を `origin/main`（`3ba2640`）から読んだ。
- 向こうの `apps/web` を `vp build` し、`vp preview` で `/login` を開いた。
- こちらの `frontend/apps/web/vite.config.ts`（CSP の文字列、`preview` と `server` の `headers`）、`index.html`、`public/`、`packages/ui/src/style.css`、`button.tsx`、`app-shell.tsx`、`docs/frontend/dark-mode.md`、`browser-storage-and-cache.md`、`browser-support.md`、`build-and-delivery.md`、ADR-014、ADR-025、ADR-030 を `a24fcf3` から読んだ。
- `vp check` が `public/` の JS にも Oxfmt と Oxlint を当て、`catch (e) {}` などを違反にすることを確かめた（作業の Issue で通る書き方にする）。
- `vp preview` の応答の `Content-Security-Policy` が、`vite.config.ts` の文字列（`form-action` に IdP の origin を足したもの）と同じであることを `curl -I` で確かめた。
- web-features 3.47.0 で、`prefers-color-scheme`（high、2020-01-15）、`color-scheme`（high、2022-02-03）、`light-dark`（high、2024-05-13）、`blocking-render`（Baseline でない）の状態を読んだ。
- 最新版は、Tailwind CSS 4.3.5（こちらは 4.3.3）、shadcn 4.23.0、Playwright 1.65.1（こちらは 1.63.0）、Vite+ 1.7.0（こちらは 1.6.0）、next-themes 0.4.6 である。

## 出典

- [Tailwind CSS: Dark mode](https://tailwindcss.com/docs/dark-mode)
- [Tailwind CSS: CHANGELOG.md](https://github.com/tailwindlabs/tailwindcss/blob/main/CHANGELOG.md)
- [shadcn/ui: Theming](https://ui.shadcn.com/docs/theming)
- [shadcn/ui: Dark Mode（Vite）](https://ui.shadcn.com/docs/dark-mode/vite)
- [shadcn/ui: Changelog](https://ui.shadcn.com/docs/changelog)
- [next-themes 0.4.6](https://github.com/pacocoursey/next-themes)（README の `nonce`、`dist/index.mjs`）
- [MDN: `<script>` の `blocking` 属性](https://developer.mozilla.org/en-US/docs/Web/HTML/Reference/Elements/script)
- [web-features 3.47.0](https://github.com/web-platform-dx/web-features)（`blocking-render`、`light-dark`、`color-scheme`、`prefers-color-scheme`）
- 向こう：`apps/web/src/lib/theme.ts`、`theme.test.ts`、`apps/web/src/main.tsx`、`apps/web/src/components/user-menu.tsx`、`packages/ui/src/styles/globals.css`、`docs/web-frontend/dark-mode.md`
- こちら：`docs/frontend/dark-mode.md`、`browser-storage-and-cache.md`、`browser-support.md`、`build-and-delivery.md`、ADR-014、ADR-025、ADR-030、ADR-068、`frontend/apps/web/vite.config.ts`、`frontend/packages/ui/src/style.css`

## 作業の Issue

- #663：ダークモードの手動切替を足し、保存した選択を最初の描画の前に当てる。#647 とは独立で、どちらが先でもよい。
