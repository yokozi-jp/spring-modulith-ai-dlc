const indentClasses = ["pl-0", "pl-4", "pl-8"] as const;

export function StaticClass({ depth, extra, label }: { depth: number; extra: string; label: string }) {
  const indent = indentClasses[Math.min(depth, indentClasses.length - 1)] ?? "pl-0";

  return (
    <>
      <div className={indent}>{label}</div>
      <div className={`${extra} flex`}>{label}</div>
      <div className={`flex ${indent}`}>{label}</div>
    </>
  );
}
