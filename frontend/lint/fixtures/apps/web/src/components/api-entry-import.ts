import { isTenantNotSelected } from "@frontend/api/api-error";
import { maskedCsrfToken } from "@frontend/api/csrf";

export const apiEntries = { isTenantNotSelected, maskedCsrfToken };
