/**
 * 要求の Idempotency-Key を keys に記録し、responses を順に返す MSW の resolver を作る。
 * 要求の回数が responses より多いときは、最後の応答を繰り返す。
 */
export function recordKeysAndRespond(...responses: (() => Response)[]) {
  const keys: (string | null)[] = [];
  function resolver({ request }: { request: Request }): Response {
    keys.push(request.headers.get("Idempotency-Key"));
    const respond = responses[Math.min(keys.length, responses.length) - 1];
    if (respond === undefined) {
      throw new Error("応答がない");
    }
    return respond();
  }
  return { keys, resolver };
}
