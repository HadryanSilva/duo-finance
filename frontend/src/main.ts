import { createApp } from 'vue'
import { createPinia } from 'pinia'
import '@fontsource-variable/inter'
import 'vue-sonner/style.css'
import './app/styles.css'
import App from './app/App.vue'
import { installProviders, queryClient } from './app/providers'
import { createAppRouter } from './app/router'
import { detectLocale, setLocale } from './shared/i18n'
import { initTheme } from './shared/lib/theme'

initTheme() // before mount: no flash of the wrong theme
setLocale(detectLocale()) // until the session says otherwise

const app = createApp(App)
installProviders(app, { pinia: createPinia(), router: createAppRouter(), queryClient })
app.mount('#app')
