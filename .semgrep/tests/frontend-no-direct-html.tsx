declare const element: HTMLElement;
declare const range: Range;
declare const html: string;

// ruleid: frontend-no-direct-html
element.innerHTML = html;
// ruleid: frontend-no-direct-html
element.outerHTML = html;
// ruleid: frontend-no-direct-html
element.insertAdjacentHTML("beforeend", html);
// ruleid: frontend-no-direct-html
document.write(html);
// ruleid: frontend-no-direct-html
document.writeln(html);
// ruleid: frontend-no-direct-html
window.document.write(html);
// ruleid: frontend-no-direct-html
window.document.writeln(html);
// ruleid: frontend-no-direct-html
range.createContextualFragment(html);
// ruleid: frontend-no-direct-html
new DOMParser().parseFromString(html, "text/html");
// ruleid: frontend-no-direct-html
Document.parseHTMLUnsafe(html);
// ruleid: frontend-no-direct-html
element.setHTMLUnsafe(html);
// ok: frontend-no-direct-html
const iframeElement = document.createElement("iframe");
// ruleid: frontend-no-direct-html
iframeElement.srcdoc = html;

// ruleid: frontend-no-direct-html
const frame = <iframe title="content" srcDoc={html} />;

// ok: frontend-no-direct-html
element.textContent = html;
// ok: frontend-no-direct-html
const text = <p>{html}</p>;

void frame;
void text;
