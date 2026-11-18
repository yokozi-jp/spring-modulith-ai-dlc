import { Link } from "@tanstack/react-router";

import { Primitive as Button } from "@/components/ui/primitive";

export function LinkBesideButton({ label }: { label: string }) {
  return (
    <>
      <Link to="/">{label}</Link>
      <Button render={<span />} />
    </>
  );
}
