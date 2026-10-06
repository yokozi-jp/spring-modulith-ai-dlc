# Changelog

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
