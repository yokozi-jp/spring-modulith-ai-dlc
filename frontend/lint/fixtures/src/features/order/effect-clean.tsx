import { useId } from "react";

const local = { useEffect: () => 1 };

export function NoEffects() {
  const id = useId();
  return <p id={id}>{local.useEffect()}</p>;
}
