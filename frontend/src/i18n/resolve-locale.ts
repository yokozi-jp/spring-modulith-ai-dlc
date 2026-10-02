export const supportedLocales = ["ja", "en"] as const;

type Locale = (typeof supportedLocales)[number];

export const defaultLocale: Locale = "ja";

// i18next の supportedLngs による解決は、完全一致する候補を language subtag だけ一致する候補より
// 優先するため、["ja-JP", "en"] で en を選ぶ。利用者の優先順を守るため、言語の解決はここで行う。
export function resolveLocale(preferredLocales: readonly string[]): Locale {
  for (const preferredLocale of preferredLocales) {
    try {
      const { language } = new Intl.Locale(preferredLocale);
      if (language === "ja" || language === "en") {
        return language;
      }
    } catch {
      // ブラウザ外から渡された不正な language tag は無視し、次の候補を試す。
    }
  }
  return defaultLocale;
}
