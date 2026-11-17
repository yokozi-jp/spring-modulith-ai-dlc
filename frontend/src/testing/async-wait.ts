// findBy、waitFor、expect.poll が待つ上限。vite.config.ts が expect.poll に、setup.ts が Testing Library に渡す。
// testTimeout より短くし、待った対象を示すエラーで失敗させる。
// ponytail: Playwright の assertion の既定と同じ値にした。これでも足りない負荷が出たら、この値だけを上げる。
export const asyncWaitTimeoutMs = 5000;

/** release を呼ぶまで解決しない promise を作る。待ちの上限を持たず、MSW の handler や loader をテストが解放するまで止める。 */
export function createGate() {
  // oxlint-disable-next-line eslint/init-declarations -- Promise の executor が同期に代入する。
  let release!: () => void;
  // oxlint-disable-next-line promise/avoid-new -- Promise.withResolvers は tsconfig の lib（ES2023）にない。
  const promise = new Promise<void>((resolve) => {
    release = resolve;
  });
  return { promise, release };
}
