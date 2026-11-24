export const THEME_STORAGE_KEY = 'olvenio-theme';
export const DARK_MODE_QUERY = '(prefers-color-scheme: dark)';
export const THEME_COLORS = { light: '#F7F6F2', dark: '#1C1E19' };
export const THEME_META_ID = 'olvenio-theme-color';

export const themeInitializationScript = `
  let theme;
  try {
    theme = localStorage.getItem(${JSON.stringify(THEME_STORAGE_KEY)});
    if (theme === 'light' || theme === 'dark') {
      document.documentElement.dataset.theme = theme;
    }
  } catch {}
  const dark = theme === 'dark' || (theme !== 'light' && matchMedia(${JSON.stringify(DARK_MODE_QUERY)}).matches);
  document.getElementById(${JSON.stringify(THEME_META_ID)}).content =
    dark ? ${JSON.stringify(THEME_COLORS.dark)} : ${JSON.stringify(THEME_COLORS.light)};
`;
