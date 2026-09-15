export function setupCounter(element: HTMLButtonElement, formatCount: (value: number) => string) {
  let counter = 0;
  const setCounter = (count: number) => {
    counter = count;
    element.textContent = formatCount(counter);
  };
  element.addEventListener("click", () => setCounter(counter + 1));
  setCounter(0);
}
