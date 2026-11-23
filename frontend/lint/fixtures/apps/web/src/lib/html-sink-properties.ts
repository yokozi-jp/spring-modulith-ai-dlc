export function writeHtml(element: HTMLElement, document: Document, html: string) {
  element.innerHTML = html;
  element.outerHTML = html;
  element.insertAdjacentHTML("beforeend", html);
  document.createRange().createContextualFragment(html);
  element.setHTMLUnsafe(html);
  Document.parseHTMLUnsafe(html);
  new window.DOMParser();
  document.querySelector("iframe")!.srcdoc = html;
  document.write(html);
  document.writeln(html);
}
