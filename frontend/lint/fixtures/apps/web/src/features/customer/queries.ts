import { queryOptions } from "@tanstack/react-query";

export const customerQueries = queryOptions({ queryKey: ["customers"], queryFn: () => [] });
