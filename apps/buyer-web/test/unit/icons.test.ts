import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import lucide from '@iconify-json/lucide/icons.json'

const appDir = join(import.meta.dirname, '../../app')

function walk(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const p = join(dir, name)
    return statSync(p).isDirectory() ? walk(p) : [p]
  })
}

// 图标只走本地 lucide 集合、不回落接口，写错名字页面上就是空白
describe('图标', () => {
  it('页面用到的 lucide 图标都在本地集合里', () => {
    const known = new Set([...Object.keys(lucide.icons), ...Object.keys(lucide.aliases ?? {})])
    const used = new Set(walk(appDir).flatMap(f => [...readFileSync(f, 'utf8').matchAll(/i-lucide-([a-z0-9-]+)/g)].map(m => m[1]!)))
    expect([...used].filter(name => !known.has(name))).toEqual([])
  })
})
