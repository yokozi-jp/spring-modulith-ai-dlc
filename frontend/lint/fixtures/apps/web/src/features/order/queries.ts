import { queryOptions } from "@tanstack/react-query";

export const orderQueries = queryOptions({ queryKey: ["orders"], queryFn: () => [] });
