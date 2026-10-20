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
      <Button render={(props) => <Link to="/" {...props} />} />
      <Button
        render={(props) => {
          // block body の return を検出する例。arrow-body-style にも違反するが、テストは数えない。
          return <a href="/" {...props} />;
        }}
      />
    </>
  );
}
