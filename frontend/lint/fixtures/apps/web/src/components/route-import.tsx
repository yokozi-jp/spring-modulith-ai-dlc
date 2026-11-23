import { routeTree } from "@/routeTree.gen";
import { Route } from "@/routes/index";

export function RoutePath() {
  return <span>{Route.path}{String(routeTree)}</span>;
}
