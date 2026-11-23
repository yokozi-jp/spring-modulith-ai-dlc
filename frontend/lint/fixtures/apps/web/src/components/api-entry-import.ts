import { isTenantNotSelected } from "@frontend/api/api-fetch";
import { maskedCsrfToken } from "@frontend/api/csrf";

export const apiEntries = { isTenantNotSelected, maskedCsrfToken };
