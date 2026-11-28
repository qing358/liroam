export default defineAppConfig({
  ui: {
    // 主色实际取 main.css 里的 --ui-primary（品牌令牌）；这里只决定 Nuxt UI 生成的辅助色阶
    colors: { primary: 'teal', neutral: 'slate' },
  },
})
