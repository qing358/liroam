<script setup lang="ts">
import { sliderChallenge, verifySlider, type SliderChallenge } from '~/services/passport'

// 壳登录场景的拼图滑块：密码登录与发短信前都要先通过（壳按 uuid 记住结果）
const emit = defineEmits<{ passed: [] }>()
const { $buyerApi } = useNuxtApp()

const challenge = ref<SliderChallenge | null>(null)
const offset = ref(0)
const dragging = ref(false)
const state = ref<'idle' | 'checking' | 'passed' | 'failed'>('idle')
const error = ref('')
let startX = 0

const HANDLE = 40

async function reload() {
  state.value = 'idle'
  offset.value = 0
  error.value = ''
  challenge.value = await sliderChallenge($buyerApi).catch((e: Error) => {
    error.value = e.message
    return null
  })
}

function onDown(e: PointerEvent) {
  if (state.value === 'checking' || state.value === 'passed' || !challenge.value) return
  dragging.value = true
  startX = e.clientX - offset.value
  ;(e.target as HTMLElement).setPointerCapture(e.pointerId)
}

function onMove(e: PointerEvent) {
  if (!dragging.value || !challenge.value) return
  offset.value = Math.min(Math.max(0, e.clientX - startX), challenge.value.originalWidth - HANDLE)
}

async function onUp() {
  if (!dragging.value) return
  dragging.value = false
  state.value = 'checking'
  const ok = await verifySlider($buyerApi, offset.value).catch(() => false)
  if (ok) {
    state.value = 'passed'
    emit('passed')
    return
  }
  state.value = 'failed'
  setTimeout(reload, 600)
}

onMounted(reload)
defineExpose({ reload })
</script>

<template>
  <div class="select-none">
    <p v-if="error" class="text-sm text-error">
      {{ error }}
      <UButton variant="link" size="sm" label="重试" @click="reload" />
    </p>
    <div v-else-if="challenge" :style="{ width: `${challenge.originalWidth}px` }" class="max-w-full">
      <div class="relative overflow-hidden rounded-md" :style="{ height: `${challenge.originalHeight}px` }">
        <img :src="challenge.backImage" alt="" class="size-full" draggable="false">
        <img
          :src="challenge.slidingImage"
          alt=""
          class="absolute"
          draggable="false"
          :width="challenge.sliderWidth"
          :height="challenge.sliderHeight"
          :style="{ left: `${offset}px`, top: `${challenge.randomY}px` }"
        >
      </div>
      <div class="relative mt-2 h-10 rounded-md bg-elevated border border-default">
        <div class="absolute inset-y-0 left-0 rounded-md bg-primary/15" :style="{ width: `${offset + HANDLE}px` }" />
        <span class="absolute inset-0 flex items-center justify-center text-sm text-muted pointer-events-none">
          {{ state === 'passed' ? '验证通过' : state === 'failed' ? '没对准，再试一次' : '向右拖动滑块完成拼图' }}
        </span>
        <button
          type="button"
          aria-label="拖动滑块"
          class="absolute top-0 h-10 w-10 rounded-md bg-primary text-inverted flex items-center justify-center touch-none"
          :style="{ left: `${offset}px` }"
          @pointerdown="onDown"
          @pointermove="onMove"
          @pointerup="onUp"
          @pointercancel="onUp"
        >
          <UIcon :name="state === 'passed' ? 'i-lucide-check' : 'i-lucide-chevrons-right'" class="size-5" />
        </button>
      </div>
    </div>
    <USkeleton v-else class="h-48 w-full max-w-80" />
  </div>
</template>
