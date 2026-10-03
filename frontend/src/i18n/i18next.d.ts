import type ja from "./locales/ja.json";

// 既定言語の resource から message key の型を導く。
declare module "i18next" {
  interface CustomTypeOptions {
    defaultNS: "translation";
    resources: { translation: typeof ja };
  }
}
