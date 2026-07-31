import { createI18n } from "vue-i18n"
import zhCN from "./zh-CN"
import en from "./en"
import ja from "./ja"
import ko from "./ko"
import fr from "./fr"
import de from "./de"
import elementEn from "element-plus/es/locale/lang/en"
import elementZhCN from "element-plus/es/locale/lang/zh-cn"
import elementJa from "element-plus/es/locale/lang/ja"
import elementKo from "element-plus/es/locale/lang/ko"
import elementFr from "element-plus/es/locale/lang/fr"
import elementDe from "element-plus/es/locale/lang/de"

const savedLang = localStorage.getItem("language") || "zh-CN"

export const i18n = createI18n({
  legacy: false,
  locale: savedLang,
  fallbackLocale: "zh-CN",
  messages: { "zh-CN": zhCN, en, ja, ko, fr, de },
})

export const elementLocales: Record<string, any> = {
  "zh-CN": elementZhCN, en: elementEn, ja: elementJa, ko: elementKo, fr: elementFr, de: elementDe,
}

export function getElementLocale() {
  return elementLocales[i18n.global.locale.value] || elementZhCN
}
