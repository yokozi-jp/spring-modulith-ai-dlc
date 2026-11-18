import { ApiProblemError } from "@frontend/api/api-fetch";

export const sharedMessage = new ApiProblemError(500, undefined).message;
