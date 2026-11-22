<!--
  商家端单品立减独立入口，指定SKU每件减固定金额，复用活动列表。
  @author Bulbasaur
  @since 2026-11-14
-->
<template><FullDiscountList default-activity-type="SINGLE_REDUCTION" /></template>
<script setup>
import FullDiscountList from "./full-discount.vue";
defineOptions({ name: "store-single-reduction" });
</script>
