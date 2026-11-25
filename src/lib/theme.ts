// Light is the default everywhere, whatever the operating system prefers. Dark applies only after the visitor picks
// it with the toggle. The "-choice" key leaves behind values saved while the store still followed the system theme.
export const THEME_STORAGE_KEY = 'olvenio-theme-choice';
export const THEME_COLORS = { light: '#F7F6F2', dark: '#1C1E19' };
export const THEME_META_ID = 'olvenio-theme-color';

export const themeInitializationScript = `
  try {
    if (localStorage.getItem(${JSON.stringify(THEME_STORAGE_KEY)}) === 'dark') {
      document.documentElement.dataset.theme = 'dark';
      document.getElementById(${JSON.stringify(THEME_META_ID)}).content = ${JSON.stringify(THEME_COLORS.dark)};
    }
  } catch {}
`;
