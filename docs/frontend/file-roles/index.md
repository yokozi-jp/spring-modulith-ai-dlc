# ファイルの役割

- [ファイルの役割：route](route.md)：route のファイル、pathless layout、loader を作るとき、route の近くにテストを置くとき
- [ファイルの役割：feature の画面](feature-page.md)：業務機能の画面（`pages/*-page.tsx`）を作るとき、画面の責務を確かめるとき
- [ファイルの役割：feature の component](feature-component.md)：業務機能の component を作るとき、画面から component を分けるとき、form の入力欄を共有するとき
- [ファイルの役割：feature の Hook](feature-hook.md)：業務機能の custom Hook を作るか判断するとき、mutation や form の Hook を書くとき
- [ファイルの役割：feature の form の組み立て](feature-form.md)：複数の form が入力欄を共有するとき、`form.ts` を作るか判断するとき
- [ファイルの役割：feature の入力 schema](feature-schema.md)：業務フォームの入力の条件を書くとき、生成 schema で表せない条件を足すとき
- [ファイルの役割：feature の module](feature-module.md)：feature の React を使わない型、定数、関数を置くとき、状態の表示名や失敗の振り分けを書くとき
- [ファイルの役割：アプリの共有component](shared-component.md)：アプリシェルや route の既定表示、複数の feature が使う component を作るとき
- [ファイルの役割：UI primitive](ui-primitive.md)：`packages/ui` に shadcn の部品や共通の UI を足すとき
- [ファイルの役割：API層](api-layer.md)：API の呼び出し、`apiFetch`、QueryClient、Orval の生成物の使い方を変えるとき
- [ファイルの役割：lib](lib.md)：複数の feature が使う React を使わない関数や、テレメトリを書くとき
- [ファイルの役割：国際化の resource](i18n-resource.md)：表示文言の catalog、locale の解決、i18next の型を変えるとき
- [ファイルの役割：テストの道具](testing-helper.md)：テスト用の render や QueryClient、setup を足すとき
- [ファイルの役割：MSW の handler と fixture](msw-handler.md)：MSW の handler や fixture を足すとき
