# Changelog

## [0.13.0](https://github.com/yokozi-jp/spring-modulith-ai-dlc/compare/v0.12.0...v0.13.0) (2026-11-23)


### Features

* **agents:** adopt RGDD with pstack and thermos for Kiro ([#365](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/365)) ([13916d9](https://github.com/yokozi-jp/spring-modulith-ai-dlc/commit/13916d9353dca36dc55247a2475938dfd4fa81c0))
* **agents:** 隔離したスタックでアプリを操作して証拠を残す検証スキル verify-demo を加える ([#496](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/496)) ([dadc26f](https://github.com/yokozi-jp/spring-modulith-ai-dlc/commit/dadc26f640e3cd21c57f490ef4a079bc27291573))
* **api:** docs/web-api の規約のうち lint が見ていないものを Spectral の規則に足す ([#503](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/503)) ([18193c5](https://github.com/yokozi-jp/spring-modulith-ai-dlc/commit/18193c54356e1d9a8d80b16c3fd53c9d9f6fd58f)), closes [#461](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/461)
* **api:** openapi の契約に認証要件を書く ([#537](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/537)) ([14c15cf](https://github.com/yokozi-jp/spring-modulith-ai-dlc/commit/14c15cf971221f58593f8fb2e84967b1194c4b26)), closes [#464](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/464)
* **backend:** テナントをリクエストとイベントの文脈に束縛する ([#547](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/547)) ([d08c4ac](https://github.com/yokozi-jp/spring-modulith-ai-dlc/commit/d08c4ac482b04a70bb3217088633e1b27c004bc7))
* **ci:** 依存関係の脆弱性検査に dep-scan を Trivy と並べて加える ([#495](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/495)) ([386a298](https://github.com/yokozi-jp/spring-modulith-ai-dlc/commit/386a298feb1bae448f63ef7bce14cd506481c971))
* **database:** モジュールごとの DB ロールへ切り替え、アプリロールを業務のスキーマから外す ([#441](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/441)) ([faaf0d0](https://github.com/yokozi-jp/spring-modulith-ai-dlc/commit/faaf0d049563c206c2824e0f75725ed7e2f72c6c))
* **database:** 業務の表に tenant_public_id と RLS を入れる ([#600](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/600)) ([f3f0b7e](https://github.com/yokozi-jp/spring-modulith-ai-dlc/commit/f3f0b7ec5493ba506a2368a42fe98c3caa21fe1e)), closes [#377](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/377)
* **frontend:** ban useEffect with a local oxlint rule ([#400](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/400)) ([3c1b20f](https://github.com/yokozi-jp/spring-modulith-ai-dlc/commit/3c1b20f9c2145b91e00fff29c94d46a20786deb5))
* **observability:** ログと HTTP の span にテナントと利用者の ID を付ける ([#617](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/617)) ([609be65](https://github.com/yokozi-jp/spring-modulith-ai-dlc/commit/609be654ca45dcccb086f0670b129b5ca2f91a9b)), closes [#378](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/378)
* **session:** セッションを Redis から PostgreSQL の表へ移し、Redis を外す ([#628](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/628)) ([eefec5a](https://github.com/yokozi-jp/spring-modulith-ai-dlc/commit/eefec5abda5adbe089520ee002b0233e9ddee63e))
* **skills:** retroとwriting-for-agentsを導入しタスクの区切りで実行する ([#220](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/220)) ([625c127](https://github.com/yokozi-jp/spring-modulith-ai-dlc/commit/625c1278451f1600fcc43babb415d6c9563962b5))
* **skills:** 注文と決済、イベント出版の再投入、集約の保存を TLA+ で全順序検査するスキルを足す ([#502](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/502)) ([7ca0235](https://github.com/yokozi-jp/spring-modulith-ai-dlc/commit/7ca02354c8301236c63574e03a149e9684fad2e0))
* **tenancy:** テナントの管理者が所属と役割を管理する ([#552](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/552)) ([c376b92](https://github.com/yokozi-jp/spring-modulith-ai-dlc/commit/c376b928c97af0b661d88b2160992a500fa4aeb4)), closes [#379](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/379)
* **tenancy:** ログインの後にアプリの利用者を引き、作成者と更新者に利用者の ID を登録する ([#450](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/450)) ([66db822](https://github.com/yokozi-jp/spring-modulith-ai-dlc/commit/66db822ec92bdcf00880c8be6ddf24a1f37ec1bd))
* **tenancy:** ログインの後にテナントを選び、選ぶ前の業務の API を 403 にする ([#507](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/507)) ([872b163](https://github.com/yokozi-jp/spring-modulith-ai-dlc/commit/872b163f2216c0930068cb382ed0430a4a4c643b))
* **tooling:** compose-test の実行ごとに slot を取り、worktree をまたいで同時に流す ([#554](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/554)) ([63e5c9f](https://github.com/yokozi-jp/spring-modulith-ai-dlc/commit/63e5c9f840d399178e6d37b05897f771247c2c00))
* **tooling:** エージェントの commit と push をバックグラウンドで実行し、終わりを待つ task を足す ([#393](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/393)) ([8159cd3](https://github.com/yokozi-jp/spring-modulith-ai-dlc/commit/8159cd35cbd67d557306331ee6bf66eddb0f22d3))


### Bug Fixes

* **backend:** opentelemetry の cve-2026-118259 を bom と logback appender で 1.67 系に上げて直す ([#416](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/416)) ([324de9a](https://github.com/yokozi-jp/spring-modulith-ai-dlc/commit/324de9a2b8f37c39c46674432ff5ffbf87d5a8d4))
* **backend:** spring modulith を 2.1.2 に上げ、staleness monitor が処理中の再投入を failed に戻さないようにする ([#497](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/497)) ([56e0bf9](https://github.com/yokozi-jp/spring-modulith-ai-dlc/commit/56e0bf91076ccef87bfe464266a438ca2b5e501f))
* **backend:** テストで OTLP のログ exporter を作らず、出力の検査が背景の ERROR で落ちないようにする ([#485](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/485)) ([45ba12f](https://github.com/yokozi-jp/spring-modulith-ai-dlc/commit/45ba12f8a9762246f6b2ff9649fd73d25904a996)), closes [#402](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/402)
* **ci:** pin betterleaks and pass its config explicitly ([#399](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/399)) ([a0114f4](https://github.com/yokozi-jp/spring-modulith-ai-dlc/commit/a0114f4b991bda113d9a93c16d3479dfeb6dd0b9))
* **error:** コミット前の切断の前提を直し、切断の ERROR を固定するテストを足す ([#553](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/553)) ([602d693](https://github.com/yokozi-jp/spring-modulith-ai-dlc/commit/602d6937a5a3c464a6e4dc18e2427d99ec6d71a0)), closes [#489](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/489)
* **hooks:** backend直下のgradleファイルとdocs直下のmdをフックの対象にする ([#230](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/230)) ([9075d79](https://github.com/yokozi-jp/spring-modulith-ai-dlc/commit/9075d79cf6ec8e4fe5734d17902ced49ba931582))
* **tooling:** ~/.profile に shims を書く理由を ~/.local/bin より前に戻すことに直す ([#524](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/524)) ([712b68a](https://github.com/yokozi-jp/spring-modulith-ai-dlc/commit/712b68a341c3355fbf099e942d25946283514889))
* **tooling:** betterleaks の allowlist をファイル全体の除外から行単位の除外に直す ([#521](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/521)) ([c014649](https://github.com/yokozi-jp/spring-modulith-ai-dlc/commit/c014649fbb73aec6fb87160d16db4aeb51f117f2))
* **tooling:** commitlint がない worktree では agent-commit を止める ([#618](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/618)) ([cd33ad2](https://github.com/yokozi-jp/spring-modulith-ai-dlc/commit/cd33ad2a0afa4dacecc3526636a3b831f63cb7c2))
* **tooling:** git commit が失敗したら、同じ内容の commit が HEAD にあっても agent-wait を成功にしない ([#470](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/470)) ([587c9af](https://github.com/yokozi-jp/spring-modulith-ai-dlc/commit/587c9afb557023209b7ae07550a3af396a1f2b75))
* **tooling:** iwe の MCP の起動コマンドが clone の完了を待つようにする ([#634](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/634)) ([cd95563](https://github.com/yokozi-jp/spring-modulith-ai-dlc/commit/cd95563e9d3a9b05032936ec6ebc470755f8101f))
* **tooling:** kiro web でも iwe の mcp サーバーを起動できるようにする ([#482](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/482)) ([3e496c6](https://github.com/yokozi-jp/spring-modulith-ai-dlc/commit/3e496c6cfee5d130d03243654b925cc043a2e9ae))
* **tooling:** kiro-web.sh の初回の mise install で、読み取り専用の共有ディレクトリの symlink の ERROR を説明に置き換える ([#624](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/624)) ([bed46d3](https://github.com/yokozi-jp/spring-modulith-ai-dlc/commit/bed46d379292e7f749b15e3ab28d8260d7eb664b)), closes [#623](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/623)
* **tooling:** linked worktree で npm ci と lefthook run が共有のフックを書き換えないようにする ([#469](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/469)) ([0a2259f](https://github.com/yokozi-jp/spring-modulith-ai-dlc/commit/0a2259fc238efbbaf4965a69dbb5b9eb901762c6))
* **tooling:** mise の shims を ~/.profile にも通し、vs code から起動した kiro に path を渡す ([#523](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/523)) ([af9bf58](https://github.com/yokozi-jp/spring-modulith-ai-dlc/commit/af9bf58847de51086494b84946d935e73384f2da))
* **tooling:** 未 commit の変更がある間は agent-push を止める ([#606](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/606)) ([bb70c21](https://github.com/yokozi-jp/spring-modulith-ai-dlc/commit/bb70c212741fb0052b192e8c8d5899034af61a81))
* 主キーと public_id の使い分けを規約にし、ArchUnit、カタログ、Spectral で検査する ([#405](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/405)) ([9a8d8c2](https://github.com/yokozi-jp/spring-modulith-ai-dlc/commit/9a8d8c2f2ed76f237faea84830f953d014ad5a5b))

## [0.12.0](https://github.com/yokozi-jp/spring-modulith-ai-dlc/compare/v0.11.0...v0.12.0) (2026-10-21)


### Features

* **api:** csrf トークン失敗の 403 を専用の problem type で返す ([#210](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/210)) ([3ca5314](https://github.com/yokozi-jp/spring-modulith-ai-dlc/commit/3ca5314ca3e2e7f3b88b7c05b4af2c8d2c4a7ac1))
* **frontend:** 規約の検査をoxlintに加える ([#207](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/207)) ([f418ada](https://github.com/yokozi-jp/spring-modulith-ai-dlc/commit/f418adada567da7b8c79133818ad8b6c0258d2f6))


### Bug Fixes

* jsPluginの誤検出とpre-pushの変更の判定を直す ([#218](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/218)) ([7adce29](https://github.com/yokozi-jp/spring-modulith-ai-dlc/commit/7adce296201063f164c886fe677fc97fbd9ff9ed))

## [0.11.0](https://github.com/yokozi-jp/spring-modulith-ai-dlc/compare/v0.10.1...v0.11.0) (2026-10-14)


### Features

* **shared:** 失敗したイベント出版を定期のジョブで自動で再投入する ([#201](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/201)) ([6ac9ae6](https://github.com/yokozi-jp/spring-modulith-ai-dlc/commit/6ac9ae64c502842ec25286cd2b6db6e016dc5b5d))

## [0.10.1](https://github.com/yokozi-jp/spring-modulith-ai-dlc/compare/v0.10.0...v0.10.1) (2026-10-12)


### Bug Fixes

* **backend:** /error が定義のないステータスコードをそのまま返す ([#192](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/192)) ([35140c6](https://github.com/yokozi-jp/spring-modulith-ai-dlc/commit/35140c6c12449cd9f4f9ceef88cff7e2727226bd))
* **backend:** security の entry point で解決できない例外を sendError にフォールバックする ([#193](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/193)) ([9ae392e](https://github.com/yokozi-jp/spring-modulith-ai-dlc/commit/9ae392e47fd507b35130fefd4561453dc00d0c09))
* **docs:** task setup で iwe 導入時に Vite+ の npm を PATH に通す ([#198](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/198)) ([e6cbcb6](https://github.com/yokozi-jp/spring-modulith-ai-dlc/commit/e6cbcb6463d162b1d76a1a965a7106039bdd1605))
* **frontend:** untracedUrlのsemgrep誤検知を理由付きで抑止する ([#194](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/194)) ([a3f0937](https://github.com/yokozi-jp/spring-modulith-ai-dlc/commit/a3f093764409541d758826f20c4d5f7da3e204cf))
* **tooling:** taskfile の後片付けの失敗を終了コードに反映する ([#191](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/191)) ([3570a70](https://github.com/yokozi-jp/spring-modulith-ai-dlc/commit/3570a700b82ba3cc046275855568b2d05d789885))

## [0.10.0](https://github.com/yokozi-jp/spring-modulith-ai-dlc/compare/v0.9.0...v0.10.0) (2026-10-09)


### Features

* product、ordering、payment を参照業務機能として main に導入する ([#122](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/122)) ([8d3479d](https://github.com/yokozi-jp/spring-modulith-ai-dlc/commit/8d3479d6fcd10d760fbe4710931ab5700432a438))

## [0.9.0](https://github.com/yokozi-jp/spring-modulith-ai-dlc/compare/v0.8.0...v0.9.0) (2026-10-08)


### Features

* [#122](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/122) で見つかった規約と例の食い違いの残りを直す ([#161](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/161)) ([c4c5c00](https://github.com/yokozi-jp/spring-modulith-ai-dlc/commit/c4c5c00eb832997bb6b57ea37fea172906ddfc0c))
* **backend:** 業務上の失敗を shared.failure の例外で表し、404 と 422 の problem details にする ([#143](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/143)) ([ff7fa8b](https://github.com/yokozi-jp/spring-modulith-ai-dlc/commit/ff7fa8b4944c4f21a0bc64915d5c6ca185f91a5e))
* csrf の cookie を __Host- の名前にし、更新系の要求だけに header を付ける ([#157](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/157)) ([4e49e8a](https://github.com/yokozi-jp/spring-modulith-ai-dlc/commit/4e49e8acd05ebc7fbcb6b08d7177f0dfd37b846a))
* **frontend-observability:** csp 違反の報告を reporting api と webhook_event で grafana に送る ([#175](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/175)) ([ec2a12d](https://github.com/yokozi-jp/spring-modulith-ai-dlc/commit/ec2a12d88d538d2f8f7c04b28947206a70c0d644))
* **frontend:** ブラウザの fetch から sql までを tempo の 1 本の trace で追えるようにする ([#163](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/163)) ([863cfa9](https://github.com/yokozi-jp/spring-modulith-ai-dlc/commit/863cfa9b875cae03fb8b8983a07b6e319041bb80))
* **frontend:** ブラウザの例外を Faro と Collector で Grafana に出す ([#160](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/160)) ([2fa16de](https://github.com/yokozi-jp/spring-modulith-ai-dlc/commit/2fa16de23f414d747eef01b44476aadb58c25a42))
* **frontend:** 画面遷移と web vitals をセッション単位で grafana で見られるようにする ([#173](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/173)) ([84410ff](https://github.com/yokozi-jp/spring-modulith-ai-dlc/commit/84410ffdca5b88bfd57e42eaffceef51f275f659))


### Bug Fixes

* **backend:** 409 の例外に原因を付けず、種類を conflict.kind で記録する ([#162](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/162)) ([f69a437](https://github.com/yokozi-jp/spring-modulith-ai-dlc/commit/f69a437a4e5dce3df370aaeb633d8006a0952ccc))

## [0.8.0](https://github.com/yokozi-jp/spring-modulith-ai-dlc/compare/v0.7.0...v0.8.0) (2026-10-06)


### Features

* **api:** return validation problems and throw problem errors in the api client ([#123](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/123)) ([db8c3bb](https://github.com/yokozi-jp/spring-modulith-ai-dlc/commit/db8c3bbf1c2a5706c617fac85b821ead1349db9e))
* **backend:** 業務テーブルの更新と削除を TableWriter に集め、楽観的ロックの迂回を ArchUnit で禁じる ([#130](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/130)) ([e0fead3](https://github.com/yokozi-jp/spring-modulith-ai-dlc/commit/e0fead3e140c601cec9a46554a6e81a793a0de76))
* **ci:** add authenticated owasp zap dast ([#126](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/126)) ([8db8a42](https://github.com/yokozi-jp/spring-modulith-ai-dlc/commit/8db8a429bb3e6c02f6eb787b416616b94c07c74c))
* **frontend:** 主要なフローを実ブラウザで確かめる E2E テスト基盤を Playwright で導入する ([#128](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/128)) ([9049c94](https://github.com/yokozi-jp/spring-modulith-ai-dlc/commit/9049c94da2d92e75662419cc3795d2276fde711f))


### Bug Fixes

* **backend:** 未認証で許可する health を liveness と readiness に限定する ([#142](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/142)) ([492d495](https://github.com/yokozi-jp/spring-modulith-ai-dlc/commit/492d4958b2b0f63078a93cb7c553aa30182c34a9))
* **ci:** scan only main history in betterleaks and harden dast sarif upload ([#133](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/133)) ([ca2e9e9](https://github.com/yokozi-jp/spring-modulith-ai-dlc/commit/ca2e9e954c9909fe8a95cdca86bd187172d983c5))
* ログアウトの e2e と csrf の拒否を確かめ、preview の csp の form-action に idp の origin を含める ([#135](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/135)) ([b24b13e](https://github.com/yokozi-jp/spring-modulith-ai-dlc/commit/b24b13e72c80abf92216e6cab5dcf342d08a2f2a))

## [0.7.0](https://github.com/yokozi-jp/spring-modulith-ai-dlc/compare/v0.6.0...v0.7.0) (2026-10-04)


### Features

* **api:** openapi 契約の品質ガードと orval で API client を生成する開発フローを足す ([#117](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/117)) ([ca4e98c](https://github.com/yokozi-jp/spring-modulith-ai-dlc/commit/ca4e98c0a8e040523fce84ec0b591d705a0f4c48))
* **database:** 楽観的ロックをupdateの更新件数で判定し、接続ごとにdbの待ち時間の上限を設定する ([#112](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/112)) ([6222cca](https://github.com/yokozi-jp/spring-modulith-ai-dlc/commit/6222cca3ae6b11786831dc76cadcaecba4538f95))
* **frontend:** ログアウト後のランディングページを追加 ([#119](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/119)) ([8659785](https://github.com/yokozi-jp/spring-modulith-ai-dlc/commit/865978542aacfce15b73a2e11ee5fe118ea26386))

## [0.6.0](https://github.com/yokozi-jp/spring-modulith-ai-dlc/compare/v0.5.0...v0.6.0) (2026-10-04)


### Features

* **shared:** jOOQの共通カラムの共通処理とpgm_cdの束縛を追加する ([#100](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/100)) ([0004af9](https://github.com/yokozi-jp/spring-modulith-ai-dlc/commit/0004af91df05bfb984a491bf6fb3487830aa0685))

## [0.5.0](https://github.com/yokozi-jp/spring-modulith-ai-dlc/compare/v0.4.0...v0.5.0) (2026-10-03)


### Features

* **frontend:** enforce feature boundaries, router state views, and generated-file guards ([#106](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/106)) ([ade268a](https://github.com/yokozi-jp/spring-modulith-ai-dlc/commit/ade268a212b6c8a346b147f005b28d49ed5cc4a5))

## [0.4.0](https://github.com/yokozi-jp/spring-modulith-ai-dlc/compare/v0.3.0...v0.4.0) (2026-10-03)


### Features

* **datetime:** 絶対時刻をマイクロ秒精度にそろえ、AWS の規約を削除する ([#101](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/101)) ([d155906](https://github.com/yokozi-jp/spring-modulith-ai-dlc/commit/d15590638e79434b7ea9a7d992e05854e3ac8ec5))


### Bug Fixes

* **datetime:** clock を迂回する現在時刻の取得を archunit で拒否し業務タイムゾーンの規則を定める ([#102](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/102)) ([623153a](https://github.com/yokozi-jp/spring-modulith-ai-dlc/commit/623153a22b61d5c6d01cffb9d7561f07d2d3bedd))

## [0.3.0](https://github.com/yokozi-jp/spring-modulith-ai-dlc/compare/v0.2.0...v0.3.0) (2026-10-03)


### Features

* **backend:** 可観測性データの出口を OpenTelemetry Collector に集約する ([#90](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/90)) ([dd8a9aa](https://github.com/yokozi-jp/spring-modulith-ai-dlc/commit/dd8a9aac052ea5fabe4c5c8a36ad648d2293ae36))

## [0.2.0](https://github.com/yokozi-jp/spring-modulith-ai-dlc/compare/v0.1.2...v0.2.0) (2026-10-03)


### Features

* **frontend:** 国際化を i18next と react-i18next へ移行する ([#91](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/91)) ([e879baa](https://github.com/yokozi-jp/spring-modulith-ai-dlc/commit/e879baa2c2f12aff446fafa7cdb342ea97189ccb))

## [0.1.2](https://github.com/yokozi-jp/spring-modulith-ai-dlc/compare/v0.1.1...v0.1.2) (2026-09-30)


### Bug Fixes

* **frontend:** rename vite config test to avoid IDE config double-load ([#75](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/75)) ([64369d2](https://github.com/yokozi-jp/spring-modulith-ai-dlc/commit/64369d2138c316365b06f8bde962eb578464e8ef))

## [0.1.1](https://github.com/yokozi-jp/spring-modulith-ai-dlc/compare/v0.1.0...v0.1.1) (2026-09-30)


### Bug Fixes

* **deps:** jackson-databind の CVE 対応（BOM を修正版へ） ([#72](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/72)) ([3adeb87](https://github.com/yokozi-jp/spring-modulith-ai-dlc/commit/3adeb87b061be015f0ccc73834fa0f5706d592ab))

## [0.1.0](https://github.com/yokozi-jp/spring-modulith-ai-dlc/compare/v0.0.1...v0.1.0) (2026-09-29)


### Features

* **frontend:** add UI, data, and testing foundations ([#70](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/70)) ([160c677](https://github.com/yokozi-jp/spring-modulith-ai-dlc/commit/160c677d248d42f2c067fad0dd2c95c978af678b))
* 耐障害性・容量ガードレールとサプライチェーン強化を追加する ([#48](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/48)) ([6ad6a72](https://github.com/yokozi-jp/spring-modulith-ai-dlc/commit/6ad6a72eda2a79adba6c0d49a51d7f963c3c6be8))

## Changelog

このファイルは release-please が Conventional Commits から自動更新する。

リリース済みの変更は、最初の Release Pull Request から版ごとに記録する。
