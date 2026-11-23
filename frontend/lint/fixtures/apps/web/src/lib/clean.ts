import { ApiProblemError } from "@frontend/api/api-error";

export const sharedMessage = new ApiProblemError(500, undefined).message;
