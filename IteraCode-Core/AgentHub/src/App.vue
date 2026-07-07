<template>
  <el-config-provider :locale="elLocale">
    <BasicLayout />
  </el-config-provider>
</template>

<script setup lang="ts">
import { computed } from "vue"
import { getElementLocale } from "@/locales"
import BasicLayout from "./layout/BasicLayout.vue"

const elLocale = computed(() => getElementLocale())
</script>

<style scoped lang="less"></style>
