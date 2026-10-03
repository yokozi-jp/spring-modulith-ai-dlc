export const parseHtml = (input: string) => new DOMParser().parseFromString(input, "text/html");
