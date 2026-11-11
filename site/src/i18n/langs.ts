// The site's languages and localized pages.

/** Page id and its directory. Only these pages are localized; the changelog, the legal pages,
 * security and signing stay in English and localized pages link to the English copies. */
export const PAGES = {
  home: '',
  features: 'features/',
  download: 'download/',
  install: 'install/',
  android_tv: 'android-tv/',
  android: 'android/',
  desktop: 'desktop/',
  fire_tv: 'fire-tv/',
  faq: 'faq/',
  formats: 'formats/',
  translate: 'translate/',
  guides: 'guides/',
  guide_android_tv: 'guides/telegram-videos-on-android-tv/',
  guide_fire_tv: 'guides/telegram-videos-on-fire-tv/',
  guide_no_casting: 'guides/telegram-on-tv-without-casting/',
  guide_smart_tv: 'guides/telegram-on-samsung-or-lg-tv/',
  guide_cast: 'guides/cast-telegram-videos-to-chromecast/',
  guide_external: 'guides/external-player-for-telegram/',
  alternatives: 'alternatives/',
} as const;
export type PageId = keyof typeof PAGES;

export interface Lang {
  /** catalog file name in site/i18n/ */
  tag: string;
  /** URL prefix, empty for English */
  path: string;
  /** what search engines are told (the language alone where one region stands for all) */
  hreflang: string;
  /** og:locale */
  og: string;
  /** the language's name for itself */
  name: string;
  dir?: 'rtl';
  /** A representative country's flag, decoration only (a language is not one country: this is a
      scanning aid beside the name, never the only label). */
  flag?: string;
}

/** The languages besides English, in the order the picker lists them. */
export const LANGS: Lang[] = [
  { tag: 'es-419', path: 'es', hreflang: 'es', og: 'es_LA', name: 'Español', flag: '🌎' },
  { tag: 'pt-BR', path: 'pt', hreflang: 'pt', og: 'pt_BR', name: 'Português', flag: '🇧🇷' },
  { tag: 'fr', path: 'fr', hreflang: 'fr', og: 'fr_FR', name: 'Français', flag: '🇫🇷' },
  { tag: 'de', path: 'de', hreflang: 'de', og: 'de_DE', name: 'Deutsch', flag: '🇩🇪' },
  { tag: 'it', path: 'it', hreflang: 'it', og: 'it_IT', name: 'Italiano', flag: '🇮🇹' },
  { tag: 'pl', path: 'pl', hreflang: 'pl', og: 'pl_PL', name: 'Polski', flag: '🇵🇱' },
  { tag: 'cs', path: 'cs', hreflang: 'cs', og: 'cs_CZ', name: 'Čeština', flag: '🇨🇿' },
  { tag: 'ru', path: 'ru', hreflang: 'ru', og: 'ru_RU', name: 'Русский', flag: '🇷🇺' },
  { tag: 'uk', path: 'uk', hreflang: 'uk', og: 'uk_UA', name: 'Українська', flag: '🇺🇦' },
  { tag: 'tr', path: 'tr', hreflang: 'tr', og: 'tr_TR', name: 'Türkçe', flag: '🇹🇷' },
  { tag: 'id', path: 'id', hreflang: 'id', og: 'id_ID', name: 'Bahasa Indonesia', flag: '🇮🇩' },
  { tag: 'vi', path: 'vi', hreflang: 'vi', og: 'vi_VN', name: 'Tiếng Việt', flag: '🇻🇳' },
  { tag: 'ar', path: 'ar', hreflang: 'ar', og: 'ar_AR', name: 'العربية', dir: 'rtl', flag: '🇸🇦' },
  { tag: 'fa', path: 'fa', hreflang: 'fa', og: 'fa_IR', name: 'فارسی', dir: 'rtl', flag: '🇮🇷' },
  { tag: 'zh-CN', path: 'zh', hreflang: 'zh-Hans', og: 'zh_CN', name: '简体中文', flag: '🇨🇳' },
  { tag: 'zh-TW', path: 'zh-tw', hreflang: 'zh-Hant', og: 'zh_TW', name: '繁體中文', flag: '🇹🇼' },
  { tag: 'ja', path: 'ja', hreflang: 'ja', og: 'ja_JP', name: '日本語', flag: '🇯🇵' },
  { tag: 'ko', path: 'ko', hreflang: 'ko', og: 'ko_KR', name: '한국어', flag: '🇰🇷' },
  { tag: 'hi', path: 'hi', hreflang: 'hi', og: 'hi_IN', name: 'हिन्दी', flag: '🇮🇳' },
  { tag: 'bn', path: 'bn', hreflang: 'bn', og: 'bn_BD', name: 'বাংলা', flag: '🇧🇩' },
  { tag: 'ml', path: 'ml', hreflang: 'ml', og: 'ml_IN', name: 'മലയാളം', flag: '🇮🇳' },
];
export const ENGLISH: Lang = { tag: 'en', path: '', hreflang: 'en', og: 'en_GB', name: 'English', flag: '🇬🇧' };

/** A language is published only once its catalog covers this share of the keys. Until then it
 * is not generated at all, so a half-translated catalog never puts English pages under /es/. */
export const PUBLISH_THRESHOLD = 0.95;

/** Pages that stay in English but carry the same notice as every other page. */
export const ENGLISH_ONLY = ['changelog/', 'legal/', 'privacy/', 'security/', 'signing/', 'support/'];

export const ORIGIN = 'https://tmplayer.org';
export const KAO_URL = 'https://keepandroidopen.org/';
