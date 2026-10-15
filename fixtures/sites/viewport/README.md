# Viewport Fixture

A static page with one known cross-engine break. `tools/browsers/layout.mjs` loads it in Playwright 1.63.0's Chromium, Firefox and WebKit at three viewports and writes one capsule per engine and viewport into `capsules/`. `drift diagnose` compares any two of them.

## Page

`index.html` and `style.css` hold four boxes that carry `data-drift` ids: `header`, `grid`, `panel` and `footer`. Every box has a fixed height, so the engines' font metrics do not change the layout.

- `header` is a column below 600 px and a row from 600 px.
- `grid` is `repeat(auto-fit, minmax(160px, 1fr))` over six 80 px cards.
- `panel` runs a paused `height: 0` to `height: auto` animation, 0.25 s into a 1 s linear run, with `interpolate-size: allow-keywords` on the root. An engine that supports the property interpolates and the panel is a quarter of its content height. An engine without it treats the animation as discrete and keeps the first keyframe.
- `footer` sits below the panel, so the panel height moves its `y`.

## Capsules

Each capsule has 34 static attributes: `browser.engine.*`, `browser.playwright.version`, `browser.viewport.*`, `browser.dpr`, `browser.css.supports.*` (the result of `CSS.supports` for five declarations), `browser.media.*` (`matchMedia` for seven queries) and `browser.layout.<id>.{x,y,width,height}` for the four boxes. All of them map to the browser dimension.

Regenerate with:

```sh
cd tools/browsers
npm ci
node layout.mjs --dir ../../fixtures/sites/viewport --out ../../fixtures/sites/viewport/capsules
```

`--viewport WxH[@DPR]` and `--select ID=SELECTOR` replace the three default viewports and the `data-drift` boxes. `--url` captures a page that is already served.

## Measured Layout

Chromium 153.0.8010.12, Firefox 155.0 and WebKit 26.6 (Playwright's build, not a Safari release), Playwright 1.63.0 on macOS arm64. `panel` height in px, with `footer` `y` in brackets:

| viewport | chromium | firefox | webkit |
| --- | --- | --- | --- |
| 390x844 | 45 (453) | 0 (408) | 0 (408) |
| 768x1024 | 45 (317) | 0 (272) | 0 (272) |
| 1280x800 | 45 (221) | 0 (176) | 0 (176) |

`header` is 104, 64 and 64 px tall and `grid` is 304, 208 and 112 px tall at the three viewports in all three engines.

`CSS.supports` per engine at the same versions:

| declaration | chromium | firefox | webkit |
| --- | --- | --- | --- |
| `interpolate-size: allow-keywords` | true | false | false |
| `corner-shape: squircle` | true | false | true |
| `position-anchor: --a` | true | true | true |
| `field-sizing: content` | true | true | true |
| `text-box-trim: trim-both` | true | true | true |

Browser Compat Data lists `interpolate-size` as Chrome 129 and later, Firefox no and Safari no, which matches. It lists `corner-shape` for Safari only as `preview`, and Playwright's WebKit supports it, so BCD is a citation for the property and the table above is the measurement for the engines that were run. `CSS.supports` cannot see a partial implementation such as `position-anchor` in WebKit.

## Diagnosis

`rules/browser/browser-css-interpolate-size-support-divergence.yml` and `rules/browser/browser-viewport-width-changes-layout.yml` match on `browser.css.supports.interpolate-size` and `browser.viewport.width`. For every ordered pair of the nine capsules, `ViewportDiagnosisTest` checks that the candidates ranked `known` are exactly the changed causes: the support key when one side is Chromium and the other is not, the viewport width when the widths differ, both when both hold, and none for Firefox against WebKit at one width.
