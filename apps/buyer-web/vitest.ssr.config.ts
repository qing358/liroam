import { defineConfig } from 'vitest/config'

// 服务端渲染测试：起构建产物 .output 与假 buyer-api，断言选卡页 HTML 源码（npm run build 之后 npm run test:ssr）
export default defineConfig({
  test: {
    include: ['test/ssr/**/*.test.ts'],
    environment: 'node',
    testTimeout: 15_000,
  },
})
