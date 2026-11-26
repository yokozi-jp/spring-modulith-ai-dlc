import { useState } from "react";

import { orderTotal } from "@/features/order/order-total";

export function useOrderDraft() {
  return useState(orderTotal);
}
