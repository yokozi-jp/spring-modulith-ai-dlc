import { Link } from "@tanstack/react-router";

import { Button } from "@frontend/ui/button";

export function A11yEmptyLinkButton() {
  return (
    <>
      <Link to="/orders" />
      <Button />
    </>
  );
}
