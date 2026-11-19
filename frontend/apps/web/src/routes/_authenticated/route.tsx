import { createFileRoute } from "@tanstack/react-router";

import { AppShell } from "@/components/app-shell";

// テナントを選ぶ画面もログインが要るため、テナントの確認は配下の _tenant の beforeLoad に置く。
export const Route = createFileRoute("/_authenticated")({ component: AppShell });
