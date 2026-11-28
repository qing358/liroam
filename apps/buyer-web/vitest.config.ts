import { defineConfig } from 'vitest/config'

// 单测只覆盖不依赖 Nuxt 运行时的纯模块（会话、API 客户端、终端与支付方式）；需要 Nuxt 环境的组件测试用 @nuxt/test-utils 另起 project
export default defineConfig({
  test: {
    include: ['test/unit/**/*.test.ts'],
    environment: 'node',
  },
})
