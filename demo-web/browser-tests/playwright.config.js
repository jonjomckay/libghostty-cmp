import {defineConfig} from '@playwright/test';
export default defineConfig({
  testDir: '.',
  reporter: 'list',
  testMatch: 'terminal.spec.js',
  timeout: 60000,
  expect: {timeout: 12000},
  workers: 1,
  use: {viewport: {width: 1280, height: 900}, locale: 'en-US', colorScheme: 'dark', trace: 'retain-on-failure'},
  projects: [
    {name: 'chromium', use: {browserName: 'chromium'}},
    {name: 'firefox', use: {browserName: 'firefox', headless: !process.env.GHOSTTY_FIREFOX_HEADED, launchOptions: {firefoxUserPrefs: {'webgl.force-enabled': true}}}},
    {name: 'webkit', use: {browserName: 'webkit', launchOptions: process.env.GHOSTTY_WEBKIT_EXECUTABLE ? {executablePath: process.env.GHOSTTY_WEBKIT_EXECUTABLE} : {}}},
  ],
});
