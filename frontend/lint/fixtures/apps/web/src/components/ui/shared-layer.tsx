import { http } from "msw";

import { orderTotal } from "@/features/order/order-total";

export const sharedLayer = { http, orderTotal };
