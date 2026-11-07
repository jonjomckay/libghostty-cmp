import {test, expect} from '@playwright/test';
import {resolve} from 'node:path';
import {pathToFileURL} from 'node:url';

const pageUrl = process.env.GHOSTTY_DEMO_URL || pathToFileURL(resolve(import.meta.dirname, '../build/offline/index.html')).href;
const screen = page => page.locator('#cmp_a11y_root [aria-label]').last();
const size = page => page.getByText(/\d+\s*×\s*\d+/);

async function command(page, text) {
  await page.keyboard.insertText(text);
  // Firefox's insertText can produce an IME commit. Let Compose process that frame before Enter.
  await page.evaluate(() => new Promise(requestAnimationFrame));
  await page.keyboard.press('Enter');
}

async function focusTerminal(page) {
  const canvas = page.locator('canvas');
  const box = await canvas.boundingBox();
  await canvas.click({position: {x: 48, y: Math.round((box?.height || 900) * 0.55)}});
  await expect(page.locator('textarea')).toBeFocused();
}

// Compose draws chrome on the Skia canvas; the a11y nodes sit under it, so clicks need force.
function chromeButton(page, name) {
  return page.getByRole('button', {name});
}

async function clickChrome(page, name) {
  await chromeButton(page, name).click({force: true});
}

test.beforeEach(async ({page}) => {
  const remote = [];
  page.on('request', request => { if (/^https?:/.test(request.url())) remote.push(request.url()); });
  page.on('pageerror', error => { throw error; });
  await page.addInitScript(() => {
    try {
      localStorage.removeItem('ghostty-demo-language');
      localStorage.removeItem('ghostty-demo-theme');
    } catch (error) {}
  });
  // file:// still runs without a local server. CJK/emoji webfonts may be fetched from font CDNs.
  await page.goto(pageUrl);
  await expect(page.locator('body')).toHaveAttribute('data-ready', 'true');
  await expect(screen(page)).toHaveAttribute('aria-label', /Type help/);
  await page.bringToFront();
  await focusTerminal(page);
  expect(remote.filter(url => !/fonts\.googleapis\.com|fonts\.gstatic\.com|jsdelivr\.net/.test(url))).toEqual([]);
});

test('keyboard, Chinese text, clear focus and Tab completion', async ({page}) => {
  await command(page, 'echo keyboard 中文');
  await expect(screen(page)).toHaveAttribute('aria-label', /\nkeyboard 中文\n/);
  await clickChrome(page, /^Clear$/);
  await expect(screen(page)).toHaveAttribute('aria-label', /^guest:~ \$$/);
  await page.keyboard.type('pw');
  await page.keyboard.press('Tab');
  await page.keyboard.press('Enter');
  await expect(screen(page)).toHaveAttribute('aria-label', /\n\/home\/guest\n/);
  await page.keyboard.type('cat examples/a');
  await page.keyboard.press('Tab');
  await page.keyboard.press('Enter');
  await expect(screen(page)).toHaveAttribute('aria-label', /Bold.*Italic.*Underline/s);
});

test('showcase, emoji shaping, command history and control bytes', async ({page}) => {
  await clickChrome(page, /^Run example$/);
  await expect(screen(page)).toHaveAttribute('aria-label', /😀 😎 🚀 🎉 ❤️ 👍🏽 👨‍👩‍👧‍👦 🇨🇳 🇺🇳/);
  await page.screenshot({path: `test-results/showcase-${test.info().project.name}.png`});
  await clickChrome(page, /^Clear$/);
  await command(page, 'echo -e "12345\\rABC"');
  await expect(screen(page)).toHaveAttribute('aria-label', /\nABC45\n/);
  await page.keyboard.press('ArrowUp');
  await page.keyboard.press('Enter');
  await expect(screen(page)).toHaveAttribute('aria-label', /ABC45\nguest:~ \$ echo -e/);
});

test('composition commits once and resize preserves terminal content', async ({page}) => {
  await page.keyboard.type('echo ');
  const editor = page.locator('textarea');
  await editor.evaluate(element => {
    element.dispatchEvent(new CompositionEvent('compositionstart', {data: '', bubbles: true}));
    element.dispatchEvent(new InputEvent('beforeinput', {inputType: 'insertCompositionText', data: '你好', isComposing: true, bubbles: true, cancelable: true}));
    element.dispatchEvent(new CompositionEvent('compositionupdate', {data: '你好', bubbles: true}));
    element.dispatchEvent(new CompositionEvent('compositionend', {data: '你好', bubbles: true}));
  });
  // Compose batches native IME events per animation frame; submit only after composition has committed.
  await expect(screen(page)).toHaveAttribute('aria-label', /echo 你好$/);
  await page.keyboard.press('Enter');
  await expect(screen(page)).toHaveAttribute('aria-label', /\n你好\n/);
  const oldSize = await size(page).textContent();
  await page.setViewportSize({width: 600, height: 850});
  await expect(size(page)).not.toHaveText(oldSize);
  await expect(screen(page)).toHaveAttribute('aria-label', /你好/);
  await focusTerminal(page);
  await command(page, 'echo resized');
  await expect(screen(page)).toHaveAttribute('aria-label', /\nresized\n/);
});

test('paste events send text through terminal paste encoding', async ({page}) => {
  await page.evaluate(() => {
    const event = new Event('paste', {bubbles: true, cancelable: true, composed: true});
    Object.defineProperty(event, 'clipboardData', {value: {getData: () => 'echo pasted'}});
    document.dispatchEvent(event);
  });
  await page.keyboard.press('Enter');
  await expect(screen(page)).toHaveAttribute('aria-label', /\npasted\n/);
});

test('OSC 52 clipboard writes require confirmation', async ({page}) => {
  const dialog = page.waitForEvent('dialog');
  const typing = command(page, 'echo -e "\\e]52;c;SGVsbG8=\\x07"');
  const prompt = await dialog;
  expect(prompt.type()).toBe('confirm');
  expect(prompt.message()).toContain('replace your clipboard');
  await prompt.dismiss();
  await typing;
  await command(page, 'echo still running');
  await expect(screen(page)).toHaveAttribute('aria-label', /\nstill running\n/);
});

test('paste button uses the asynchronous clipboard host', async ({page}) => {
  await page.evaluate(() => {
    Object.defineProperty(navigator, 'clipboard', {configurable: true, value: {
      readText: () => Promise.resolve('echo async paste')
    }});
  });
  await clickChrome(page, /^Paste$/);
  await expect(screen(page)).toHaveAttribute('aria-label', /echo async paste$/);
  await page.keyboard.press('Enter');
  await expect(screen(page)).toHaveAttribute('aria-label', /\nasync paste\n/);
});

test('browser paste shortcut does not also read the clipboard API', async ({page}) => {
  await page.evaluate(() => {
    globalThis.clipboardReadCount = 0;
    Object.defineProperty(navigator, 'clipboard', {configurable: true, value: {
      readText: () => { globalThis.clipboardReadCount++; return Promise.resolve('duplicate'); }
    }});
  });
  await page.locator('textarea').evaluate(editor => {
    editor.dispatchEvent(new KeyboardEvent('keydown', {key: 'V', code: 'KeyV', ctrlKey: true, shiftKey: true, bubbles: true, composed: true}));
    const event = new Event('paste', {bubbles: true, cancelable: true, composed: true});
    Object.defineProperty(event, 'clipboardData', {value: {getData: () => 'echo once'}});
    editor.dispatchEvent(event);
    editor.dispatchEvent(new KeyboardEvent('keyup', {key: 'V', code: 'KeyV', ctrlKey: true, shiftKey: true, bubbles: true, composed: true}));
  });
  await expect(screen(page)).toHaveAttribute('aria-label', /echo once$/);
  await page.keyboard.press('Enter');
  await expect(screen(page)).toHaveAttribute('aria-label', /\nonce\n/);
  expect(await page.evaluate(() => globalThis.clipboardReadCount)).toBe(0);
});

test.describe('browser language and color scheme defaults', () => {
  test.use({locale: 'zh-CN', colorScheme: 'light'});
  test('chrome follows Chinese locale and light appearance', async ({page}) => {
    await expect(chromeButton(page, /^清屏$/)).toBeVisible();
    await expect(chromeButton(page, /^运行示例$/)).toBeVisible();
    await expect(chromeButton(page, /^跟随系统$/)).toBeVisible();
    await expect.poll(() => page.evaluate(() => getComputedStyle(document.body).backgroundColor)).toBe('rgb(250, 250, 251)');
  });
});
