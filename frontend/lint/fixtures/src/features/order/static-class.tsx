const indentClasses = ["pl-0", "pl-4", "pl-8"] as const;
const toneClasses: Record<string, string> = { "danger-strong": "bg-destructive" };

export function StaticClass({
  depth,
  extra,
  tone,
  label,
}: {
  depth: number;
  extra: string;
  tone: string;
  label: string;
}) {
  const indent = indentClasses[Math.min(depth, indentClasses.length - 1)] ?? "pl-0";

  return (
    <>
      <div className={indent}>{label}</div>
      <div className={`${extra} flex`}>{label}</div>
      <div className={`flex ${indent}`}>{label}</div>
      <div className={toneClasses[`${tone}-strong`]}>{label}</div>
    </>
  );
}
