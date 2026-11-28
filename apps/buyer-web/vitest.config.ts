import { fileURLToPath } from 'node:url'
import { defineConfig } from 'vitest/config'

// 单测只覆盖不依赖 Nuxt 运行时的纯模块（会话、API 客户端、终端与支付方式、目的地与交付进度）；~ 同 Nuxt 指 app/
// 服务端渲染测试另见 test/ssr（npm run test:ssr，需先 npm run build）
export default defineConfig({
  resolve: { alias: { '~': fileURLToPath(new URL('./app', import.meta.url)) } },
  test: {
    include: ['test/unit/**/*.test.ts'],
    environment: 'node',
  },
})
