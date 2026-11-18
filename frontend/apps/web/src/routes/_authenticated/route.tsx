import { createFileRoute } from "@tanstack/react-router";

import { AppShell } from "@/components/app-shell";

// 権限の API ができたら、配下の route に共通する beforeLoad をここに置く。
export const Route = createFileRoute("/_authenticated")({ component: AppShell });
