import { Link } from "@tanstack/react-router";

import { Primitive as Button } from "@/components/ui/primitive";

export function ButtonInLink({ label }: { label: string }) {
  return (
    <>
      <Link to="/">
        <Button />
      </Link>
      <a href="/">
        <span>
          <button type="button">{label}</button>
        </span>
      </a>
      <Button render={<Link to="/" />} />
    </>
  );
}
