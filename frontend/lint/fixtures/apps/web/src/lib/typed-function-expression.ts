type Formatter = (value: number) => string;

export const format: Formatter = function format(value) {
  return value.toFixed(2);
};
