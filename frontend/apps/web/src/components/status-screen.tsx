import { buttonVariants } from "@frontend/ui/button";
import { useTranslation } from "react-i18next";

/** アプリシェルの外で、状態の見出しと説明と、ページ全体を遷移するリンクを出す画面。 */
export function StatusScreen({
  heading,
  description,
  actionLabel,
  actionHref,
}: {
  heading: string;
  description?: string;
  actionLabel: string;
  actionHref: string;
}) {
  const { t } = useTranslation();

  return (
    <div className="min-h-svh bg-background text-foreground">
      <title>{t("title")}</title>
      <main>
        <section className="flex flex-col items-start gap-4 px-6 py-16 sm:px-10">
          <h1 className="text-2xl font-semibold">{heading}</h1>
          {description === undefined ? undefined : (
            <p className="text-muted-foreground">{description}</p>
          )}
          {/* ログインは backend の OAuth2 の入口なので、router の Link ではなく全画面遷移にする。 */}
          <a href={actionHref} className={buttonVariants({ variant: "outline" })}>
            {actionLabel}
          </a>
        </section>
      </main>
    </div>
  );
}
