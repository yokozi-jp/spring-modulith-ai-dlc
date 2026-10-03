import { customerName } from "@/features/customer/customer-name";

export { customerLabel } from "@/features/customer/customer-name";

export const orderCustomer = `${customerName}/order`;
export const loadCustomer = () => import("@/features/customer/customer-name");
