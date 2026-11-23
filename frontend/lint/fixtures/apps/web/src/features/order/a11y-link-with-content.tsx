import { Link } from "@tanstack/react-router";

export function A11yLinkWithContent({ label }: { label: string }) {
  return <Link to="/orders">{label}</Link>;
}
