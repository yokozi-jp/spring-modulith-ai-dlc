type Formatter = (value: number) => string;

// oxlint-disable-next-line func-style -- Formatter から value の型を推論させる。
export const format: Formatter = (value) => value.toFixed(2);

export function formatAll(values: readonly number[]): string[] {
  return values.map((value) => format(value));
}
