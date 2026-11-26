import { isTenantNotSelected } from "@frontend/api/api-error";
import { maskedCsrfToken } from "@frontend/api/csrf";
import { loginPath } from "@frontend/api/unauthorized-redirect";

export const apiEntries = { isTenantNotSelected, loginPath, maskedCsrfToken };
