import "./style.css";
import typescriptLogo from "./assets/typescript.svg";
import viteLogo from "./assets/vite.svg";
import heroImg from "./assets/hero.png";
import { setupCounter } from "./counter.ts";
import { resolveMessages } from "./i18n.ts";

const { locale, messages } = resolveMessages(navigator.languages);
document.documentElement.lang = locale;
document.title = messages.title;

document.querySelector<HTMLDivElement>("#app")!.innerHTML = `
<section id="center">
  <div class="hero">
    <img src="${heroImg}" class="base" width="170" height="179" alt="">
    <img src="${typescriptLogo}" class="framework" alt="TypeScript logo">
    <img src="${viteLogo}" class="vite" alt="Vite logo">
  </div>
  <div>
    <h1>${messages.heading}</h1>
    <p>${messages.intro}</p>
  </div>
  <button id="counter" type="button" class="counter"></button>
</section>

<div class="ticks"></div>

<section id="next-steps">
  <div id="docs">
    <svg class="icon" role="presentation" aria-hidden="true"><use href="/icons.svg#documentation-icon"></use></svg>
    <h2>${messages.documentationHeading}</h2>
    <p>${messages.documentationLead}</p>
    <ul>
      <li>
        <a href="https://vite.dev/" target="_blank" rel="noopener noreferrer">
          <img class="logo" src="${viteLogo}" alt="">
          ${messages.exploreVite}
        </a>
      </li>
      <li>
        <a href="https://www.typescriptlang.org" target="_blank" rel="noopener noreferrer">
          <img class="button-icon" src="${typescriptLogo}" alt="">
          ${messages.learnTypeScript}
        </a>
      </li>
    </ul>
  </div>
  <div id="social">
    <svg class="icon" role="presentation" aria-hidden="true"><use href="/icons.svg#social-icon"></use></svg>
    <h2>${messages.communityHeading}</h2>
    <p>${messages.communityLead}</p>
    <ul>
      <li><a href="https://github.com/vitejs/vite" target="_blank" rel="noopener noreferrer"><svg class="button-icon" role="presentation" aria-hidden="true"><use href="/icons.svg#github-icon"></use></svg>GitHub</a></li>
      <li><a href="https://chat.vite.dev/" target="_blank" rel="noopener noreferrer"><svg class="button-icon" role="presentation" aria-hidden="true"><use href="/icons.svg#discord-icon"></use></svg>Discord</a></li>
      <li><a href="https://x.com/vite_js" target="_blank" rel="noopener noreferrer"><svg class="button-icon" role="presentation" aria-hidden="true"><use href="/icons.svg#x-icon"></use></svg>X.com</a></li>
      <li><a href="https://bsky.app/profile/vite.dev" target="_blank" rel="noopener noreferrer"><svg class="button-icon" role="presentation" aria-hidden="true"><use href="/icons.svg#bluesky-icon"></use></svg>Bluesky</a></li>
    </ul>
  </div>
</section>

<div class="ticks"></div>
<section id="spacer"></section>
`;

setupCounter(document.querySelector<HTMLButtonElement>("#counter")!, messages.count);
