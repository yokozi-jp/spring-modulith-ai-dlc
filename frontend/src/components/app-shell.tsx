import { Outlet } from "@tanstack/react-router";
import { useRef } from "react";
import type { SubmitEvent } from "react";
import { useTranslation } from "react-i18next";

import { Button } from "@/components/ui/button";
import { maskedCsrfToken } from "@/lib/csrf";
import { resetTelemetrySession } from "@/lib/telemetry";

async function submitAfterSessionReset(form: HTMLFormElement): Promise<void> {
  try {
    await resetTelemetrySession();
  } catch {
    // テレメトリの失敗で logout を止めない。
  }
  HTMLFormElement.prototype.submit.call(form);
}

export function AppShell() {
  const { t } = useTranslation();
  const logoutStarted = useRef(false);
  // bfcache から戻った画面は ref が true のままなので、ボタンが効かなくなる。
  // React Compiler が関数を安定させるので、listener は form の表示から破棄まで一度だけ付く。
  function watchPageShow(): () => void {
    function reopen(event: PageTransitionEvent): void {
      if (event.persisted) {
        logoutStarted.current = false;
      }
    }
    globalThis.addEventListener("pageshow", reopen);
    return () => {
      globalThis.removeEventListener("pageshow", reopen);
    };
  }
  function submitLogout(event: SubmitEvent<HTMLFormElement>): void {
    event.preventDefault();
    if (logoutStarted.current) {
      return;
    }
    logoutStarted.current = true;
    void submitAfterSessionReset(event.currentTarget);
  }

  return (
    <div className="min-h-svh bg-background text-foreground">
      <header className="flex items-center justify-between border-b px-6 py-3 sm:px-10">
        <p className="font-semibold">{t("title")}</p>
        <form ref={watchPageShow} method="post" action="/logout" onSubmit={submitLogout}>
          {/* render 時に Cookie を読む。token はログインとログアウトの全画面遷移でしか入れ替わらないため。 */}
          <input type="hidden" name="_csrf" value={maskedCsrfToken()} />
          <Button type="submit" variant="outline" size="sm">
            {t("logout")}
          </Button>
        </form>
      </header>
      <main>
        <Outlet />
      </main>
    </div>
  );
}
