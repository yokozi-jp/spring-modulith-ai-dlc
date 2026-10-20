type Formatter = (value: number) => string;

export const format: Formatter = (value) => value.toFixed(2);

export function formatAll(values: readonly number[]): string[] {
  return values.map((value) => format(value));
}
