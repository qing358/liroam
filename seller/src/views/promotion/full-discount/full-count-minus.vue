<!--
  商家端多件优惠独立入口，仅支持按购买件数减钱，复用活动列表。
  @author Bulbasaur
  @since 2026-11-14
-->
<template>
  <FullDiscountList default-activity-type="FULL_COUNT_MINUS" />
</template>
<script setup>
import FullDiscountList from "./full-discount.vue";
defineOptions({ name: "store-full-count-minus" });
</script>
