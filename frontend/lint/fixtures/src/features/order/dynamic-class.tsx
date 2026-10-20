const toneClasses = new Map([["danger-strong", "bg-destructive"]]);

export function DynamicClass({ depth, tone, label }: { depth: number; tone: string; label: string }) {
  return (
    <>
      <div className={`pl-${depth}`}>{label}</div>
      <div className={`flex bg-${tone}-500`}>{label}</div>
      <div className={`${tone}-500`}>{label}</div>
      <div className={`${tone}${depth}`}>{label}</div>
      <div className={toneClasses.get(`${tone}-strong`)}>{label}</div>
    </>
  );
}
