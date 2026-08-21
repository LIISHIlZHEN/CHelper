import Theme from 'vitepress/theme'
import RemoteMarkdown from './components/RemoteMarkdown.vue'
import './styles.css'

export default {
  ...Theme,
  enhanceApp({ app }) {
    app.component('RemoteMarkdown', RemoteMarkdown)
  },
}