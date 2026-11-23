import { findOrderById } from "@frontend/api/endpoints/ordering/ordering";
import type { Order } from "@frontend/api/models";
import { orderSchema } from "@frontend/api/zod/ordering/ordering.zod";

export const generatedApi = { findOrderById, orderSchema };
export type GeneratedOrder = Order;
