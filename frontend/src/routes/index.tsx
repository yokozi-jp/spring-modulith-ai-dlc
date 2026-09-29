import { Button } from "@/components/ui/button";
import { createFileRoute } from "@tanstack/react-router";
import { useEffect, useState } from "react";
import { resolveMessages } from "../i18n.ts";

export const Route = createFileRoute("/")({ component: HomePage });

function HomePage() {
  const [count, setCount] = useState(0);
  const { locale, messages } = resolveMessages(navigator.languages);

  useEffect(() => {
    document.documentElement.lang = locale;
    document.title = messages.title;
  }, [locale, messages.title]);

  return (
    <main className="min-h-svh bg-background px-6 py-16 text-foreground sm:px-10">
      <div className="mx-auto flex w-full max-w-4xl flex-col gap-12">
        <section className="flex flex-col items-start gap-6" aria-labelledby="page-heading">
          <div className="rounded-full border bg-muted px-3 py-1 text-sm text-muted-foreground">
            React 19 · TanStack · Base UI
          </div>
          <div className="max-w-2xl space-y-3">
            <h1 id="page-heading" className="text-4xl font-semibold tracking-tight sm:text-5xl">
              {messages.heading}
            </h1>
            <p className="text-lg leading-8 text-muted-foreground">{messages.intro}</p>
          </div>
          <Button type="button" size="lg" onClick={() => setCount((value) => value + 1)}>
            {messages.count(count)}
          </Button>
        </section>

        <div className="grid gap-6 md:grid-cols-2">
          <section className="rounded-xl border bg-card p-6 text-card-foreground shadow-sm">
            <h2 className="text-xl font-semibold">{messages.documentationHeading}</h2>
            <p className="mt-2 text-sm leading-6 text-muted-foreground">
              {messages.documentationLead}
            </p>
            <ul className="mt-6 space-y-3">
              <li>
                <a
                  className="font-medium underline-offset-4 hover:underline focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-ring"
                  href="https://vite.dev/"
                  target="_blank"
                  rel="noopener noreferrer"
                >
                  {messages.exploreVite}
                </a>
              </li>
              <li>
                <a
                  className="font-medium underline-offset-4 hover:underline focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-ring"
                  href="https://www.typescriptlang.org"
                  target="_blank"
                  rel="noopener noreferrer"
                >
                  {messages.learnTypeScript}
                </a>
              </li>
            </ul>
          </section>

          <section className="rounded-xl border bg-card p-6 text-card-foreground shadow-sm">
            <h2 className="text-xl font-semibold">{messages.communityHeading}</h2>
            <p className="mt-2 text-sm leading-6 text-muted-foreground">{messages.communityLead}</p>
            <ul className="mt-6 space-y-3">
              <li>
                <a
                  className="font-medium underline-offset-4 hover:underline focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-ring"
                  href="https://github.com/vitejs/vite"
                  target="_blank"
                  rel="noopener noreferrer"
                >
                  GitHub
                </a>
              </li>
              <li>
                <a
                  className="font-medium underline-offset-4 hover:underline focus-visible:outline-2 focus-visible:outline-offset-4 focus-visible:outline-ring"
                  href="https://chat.vite.dev/"
                  target="_blank"
                  rel="noopener noreferrer"
                >
                  Discord
                </a>
              </li>
            </ul>
          </section>
        </div>
      </div>
    </main>
  );
}
