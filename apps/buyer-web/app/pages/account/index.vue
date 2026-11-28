<script setup lang="ts">
import { deleteAddress, listAddresses, type Address } from '~/services/address'

// 账户骨架：资料与收货地址；安全、消息、佣金、认领渠道订单由 WS12 补
definePageMeta({ middleware: 'auth' })
useHead({ title: '我的账户' })

const { $buyerApi } = useNuxtApp()
const { member, load, signOut } = useMember()
const addresses = ref<Address[]>([])

async function refreshAddresses() {
  addresses.value = await listAddresses($buyerApi).catch(() => [])
}

async function remove(id: string) {
  await deleteAddress($buyerApi, id)
  await refreshAddresses()
}

async function quit() {
  await signOut()
  await navigateTo('/', { replace: true })
}

onMounted(async () => {
  if (!member.value) await load()
  await refreshAddresses()
})
</script>

<template>
  <UContainer class="py-10 max-w-2xl space-y-4">
    <UCard>
      <div class="flex items-center gap-3">
        <UAvatar :src="member?.face" icon="i-lucide-user-round" size="lg" />
        <div class="flex-1">
          <p class="font-medium text-highlighted">
            {{ member?.nickName || member?.username }}
          </p>
          <p class="text-sm text-muted">
            {{ member?.mobile }}
          </p>
        </div>
        <UButton variant="outline" color="neutral" label="退出登录" @click="quit" />
      </div>
    </UCard>

    <UCard>
      <template #header>
        <h2 class="font-medium text-highlighted">
          收货地址
        </h2>
      </template>
      <p v-if="!addresses.length" class="text-sm text-muted">
        购买实体卡时可新增地址
      </p>
      <ul class="divide-y divide-default">
        <li v-for="a in addresses" :key="a.id" class="flex items-start gap-3 py-3">
          <div class="flex-1">
            <p class="text-highlighted">
              {{ a.name }} {{ a.mobile }}
              <UBadge v-if="a.isDefault" size="sm" variant="subtle" label="默认" />
            </p>
            <p class="text-sm text-muted">
              {{ a.consigneeAddressPath.replace(/,/g, ' ') }} {{ a.detail }}
            </p>
          </div>
          <UButton size="sm" variant="ghost" color="neutral" icon="i-lucide-trash-2" aria-label="删除地址" @click="remove(a.id)" />
        </li>
      </ul>
    </UCard>
  </UContainer>
</template>
