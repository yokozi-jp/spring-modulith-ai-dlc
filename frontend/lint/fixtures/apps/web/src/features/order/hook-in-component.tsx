export function useCounter() {
  return 1;
}

export function Counter() {
  return useCounter();
}
