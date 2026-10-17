import type { Faro } from "@grafana/faro-web-sdk";

export type OrderTelemetry = Faro;
export const loadTelemetry = async () => import("@grafana/faro-web-sdk");
