import type { Notice } from "@/features/orders/notice";

/** 常に描画する live region。output の内容は phrasing content なので、見出しは strong で表す。見出しと説明を別の要素に分け、1 つの文字列に連結しない。 */
export function ProblemNotice({ notice }: { notice: Notice | undefined }) {
  return (
    <output className="block space-y-1">
      {notice === undefined ? undefined : (
        <>
          <strong className="block">{notice.heading}</strong>
          {notice.description === undefined ? undefined : (
            <span className="block text-muted-foreground">{notice.description}</span>
          )}
          {notice.details?.map((detail) => (
            <span key={detail} className="block text-sm">
              {detail}
            </span>
          ))}
        </>
      )}
    </output>
  );
}
