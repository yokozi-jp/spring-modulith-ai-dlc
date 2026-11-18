import { Link } from "@tanstack/react-router";

import { Button } from "@frontend/ui/button";

export function LinkBesideButton({ label }: { label: string }) {
  return (
    <>
      <Link to="/">{label}</Link>
      <Button render={<span />} />
    </>
  );
}
