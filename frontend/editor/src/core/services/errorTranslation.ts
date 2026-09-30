import i18next from "i18next";

/**
 * Translate an error message with the i18next singleton that `@app/i18n`
 * configures at startup, or return the English fallback before that.
 *
 * Imported bare instead of through `@app/i18n` on purpose: the error helpers
 * are pulled in almost everywhere, and importing the config module runs its
 * `init()`, which breaks every test that mocks `react-i18next` without
 * `initReactI18next`. Named `t` so the translation audits see the keys.
 */
export const t = (key: string, fallback: string): string =>
  i18next.isInitialized ? (i18next.t(key, fallback) as string) : fallback;
