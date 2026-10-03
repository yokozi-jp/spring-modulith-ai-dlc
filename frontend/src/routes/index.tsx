import { createFileRoute } from "@tanstack/react-router";
import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";

import { Button } from "@/components/ui/button";

function HomePage() {
  const [count, setCount] = useState(0);
  const { t } = useTranslation();

  useEffect(() => {
    document.title = t("title");
  }, [t]);

  return (
    <main className="min-h-svh bg-background px-6 py-16 text-foreground sm:px-10">
      <div className="mx-auto flex w-full max-w-4xl flex-col gap-12">
        <section className="flex flex-col items-start gap-6" aria-labelledby="page-heading">
          <div className="rounded-full border bg-muted px-3 py-1 text-sm text-muted-foreground">
            {t("stack")}
          </div>
          <div className="max-w-2xl space-y-3">
            <h1 id="page-heading" className="text-4xl font-semibold tracking-tight sm:text-5xl">
              {t("heading")}
            </h1>
            <p className="text-lg leading-8 text-muted-foreground">{t("intro")}</p>
          </div>
          <Button
            type="button"
            size="lg"
            onClick={() => {
              setCount((value) => value + 1);
            }}
          >
            {t("count", { count })}
          </Button>
        </section>

        <div className="grid gap-6 md:grid-cols-2">
          <section className="rounded-xl border bg-card p-6 text-card-foreground shadow-sm">
            <h2 className="text-xl font-semibold">{t("documentationHeading")}</h2>
            <p className="mt-2 text-sm leading-6 text-muted-foreground">{t("documentationLead")}</p>
            <ul className="mt-6 space-y-3">
              <li>
                <a
                  className="font-medium underline-offset-4 hover:underline focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-ring"
                  href="https://vite.dev/"
                  target="_blank"
                  rel="noopener noreferrer"
                >
                  {t("exploreVite")}
                </a>
              </li>
              <li>
                <a
                  className="font-medium underline-offset-4 hover:underline focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-ring"
                  href="https://www.typescriptlang.org"
                  target="_blank"
                  rel="noopener noreferrer"
                >
                  {t("learnTypeScript")}
                </a>
              </li>
            </ul>
          </section>

          <section className="rounded-xl border bg-card p-6 text-card-foreground shadow-sm">
            <h2 className="text-xl font-semibold">{t("communityHeading")}</h2>
            <p className="mt-2 text-sm leading-6 text-muted-foreground">{t("communityLead")}</p>
            <ul className="mt-6 space-y-3">
              <li>
                <a
                  className="font-medium underline-offset-4 hover:underline focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-ring"
                  href="https://github.com/vitejs/vite"
                  target="_blank"
                  rel="noopener noreferrer"
                >
                  {t("github")}
                </a>
              </li>
              <li>
                <a
                  className="font-medium underline-offset-4 hover:underline focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-ring"
                  href="https://chat.vite.dev/"
                  target="_blank"
                  rel="noopener noreferrer"
                >
                  {t("discord")}
                </a>
              </li>
            </ul>
          </section>
        </div>
      </div>
    </main>
  );
}

export const Route = createFileRoute("/")({ component: HomePage });
