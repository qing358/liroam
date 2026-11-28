<script setup lang="ts">
import { z } from 'zod'
import { createAddress, listAddresses, regionChildren, type Address, type Region } from '~/services/address'

// 实体卡寄送地址：选已有地址或新建（省 / 市 / 区三级取壳地区字典）
const selected = defineModel<string | undefined>()
const { $buyerApi } = useNuxtApp()
const toast = useToast()

const addresses = ref<Address[]>([])
const adding = ref(false)
const levels = ref<Region[][]>([])
const picked = ref<string[]>([])
const form = reactive({ name: '', mobile: '', detail: '', isDefault: false })
const schema = z.object({
  name: z.string().min(1, '请输入收件人'),
  mobile: z.string().regex(/^1\d{10}$/, '请输入 11 位手机号'),
  detail: z.string().min(1, '请输入详细地址'),
})

async function refresh() {
  addresses.value = await listAddresses($buyerApi)
  if (!selected.value) selected.value = (addresses.value.find((a) => a.isDefault) ?? addresses.value[0])?.id
  adding.value = addresses.value.length === 0
}

async function loadLevel(index: number, parentId: string) {
  levels.value = levels.value.slice(0, index)
  picked.value = picked.value.slice(0, index)
  const children = await regionChildren($buyerApi, parentId)
  if (children.length) levels.value.push(children)
}

async function pick(index: number, id: string) {
  picked.value[index] = id
  if (index < 2) await loadLevel(index + 1, id)
}

async function save() {
  if (picked.value.length === 0 || picked.value.length < levels.value.length) {
    toast.add({ title: '请选择所在地区', color: 'warning' })
    return
  }
  const names = picked.value.map((id, i) => levels.value[i]?.find((r) => r.id === id)?.name ?? '')
  try {
    const created = await createAddress($buyerApi, {
      ...form,
      consigneeAddressIdPath: picked.value.join(','),
      consigneeAddressPath: names.join(','),
    })
    selected.value = created.id
    await refresh()
  }
  catch (e) {
    toast.add({ title: (e as Error).message, color: 'error' })
  }
}

const options = computed(() => addresses.value.map((a) => ({
  value: a.id,
  label: `${a.name} ${a.mobile}`,
  description: `${a.consigneeAddressPath.replace(/,/g, ' ')} ${a.detail}`,
})))

onMounted(async () => {
  await refresh()
  await loadLevel(0, '0')
})
</script>

<template>
  <div class="space-y-3">
    <template v-if="!adding">
      <URadioGroup v-model="selected" :items="options" />
      <UButton variant="link" icon="i-lucide-plus" label="新增收货地址" @click="adding = true" />
    </template>

    <UForm v-else :schema="schema" :state="form" class="grid gap-3 sm:grid-cols-2" @submit="save">
      <UFormField label="收件人" name="name">
        <UInput v-model="form.name" class="w-full" />
      </UFormField>
      <UFormField label="手机号" name="mobile">
        <UInput v-model="form.mobile" inputmode="numeric" class="w-full" />
      </UFormField>
      <UFormField label="所在地区" class="sm:col-span-2">
        <div class="grid grid-cols-3 gap-2">
          <USelect
            v-for="(level, i) in levels"
            :key="i"
            :model-value="picked[i]"
            :items="level.map((r) => ({ value: r.id, label: r.name }))"
            placeholder="请选择"
            @update:model-value="(v: unknown) => pick(i, String(v))"
          />
        </div>
      </UFormField>
      <UFormField label="详细地址" name="detail" class="sm:col-span-2">
        <UInput v-model="form.detail" class="w-full" />
      </UFormField>
      <div class="flex gap-2 sm:col-span-2">
        <UButton type="submit" label="保存地址" />
        <UButton v-if="addresses.length" variant="ghost" color="neutral" label="取消" @click="adding = false" />
      </div>
    </UForm>
  </div>
</template>
