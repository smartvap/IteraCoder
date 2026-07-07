import { createApp } from "vue"
import "./main.css"
import App from "./App.vue"

// 导入 router
import router from "@/router"
// 导入 Element Plus
import ElementPlus from "element-plus"
import "element-plus/dist/index.css"
import * as ElementPlusIconsVue from "@element-plus/icons-vue"
// 导入 pinia
import { createPinia } from "pinia"
import hljs from "highlight.js"
import "highlight.js/styles/atom-one-light.css"
// 导入 i18n
import { i18n } from "@/locales"
const pinia = createPinia()

const app = createApp(App)
// 使用 router
app.use(router)
// 使用 pinia
app.use(pinia)
// 使用 Element Plus
app.use(ElementPlus)
// 使用 i18n
app.use(i18n)
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

// 创建 v-highlight 全局指令
app.directive("highlight", function (el) {
  let blocks = el.querySelectorAll("pre code")
  blocks.forEach((block: any) => {
    hljs.highlightBlock(block)
  })
})

app.mount("#app")
