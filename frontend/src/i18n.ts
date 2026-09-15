export const supportedLocales = ["ja", "en"] as const;

export type Locale = (typeof supportedLocales)[number];

type Messages = {
  title: string;
  heading: string;
  intro: string;
  documentationHeading: string;
  documentationLead: string;
  exploreVite: string;
  learnTypeScript: string;
  communityHeading: string;
  communityLead: string;
  count: (value: number) => string;
};

const defaultLocale: Locale = "ja";

const catalog: Record<Locale, Messages> = {
  ja: {
    title: "デモアプリケーション",
    heading: "はじめる",
    intro: "src/main.ts を編集して保存すると、HMR の動作を確認できます。",
    documentationHeading: "ドキュメント",
    documentationLead: "開発に必要な情報を確認できます。",
    exploreVite: "Vite を調べる",
    learnTypeScript: "TypeScript を学ぶ",
    communityHeading: "コミュニティ",
    communityLead: "Vite コミュニティに参加できます。",
    count: (value) => `カウント：${new Intl.NumberFormat("ja").format(value)}`,
  },
  en: {
    title: "Demo application",
    heading: "Get started",
    intro: "Edit src/main.ts and save to test HMR.",
    documentationHeading: "Documentation",
    documentationLead: "Find the information needed for development.",
    exploreVite: "Explore Vite",
    learnTypeScript: "Learn TypeScript",
    communityHeading: "Community",
    communityLead: "Join the Vite community.",
    count: (value) => `Count: ${new Intl.NumberFormat("en").format(value)}`,
  },
};

export function resolveLocale(preferredLocales: readonly string[]): Locale {
  for (const preferredLocale of preferredLocales) {
    try {
      const language = new Intl.Locale(preferredLocale).language;
      if (language === "ja" || language === "en") {
        return language;
      }
    } catch {
      // ブラウザ外から渡された不正な language tag は無視し、次の候補を試す。
    }
  }
  return defaultLocale;
}

export function resolveMessages(preferredLocales: readonly string[]) {
  const locale = resolveLocale(preferredLocales);
  return { locale, messages: catalog[locale] };
}
