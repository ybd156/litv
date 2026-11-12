<script setup lang="ts">
import { NConfigProvider, NDialogProvider, NMessageProvider, darkTheme, dateZhCN, zhCN } from 'naive-ui'
import AppShell from './AppShell.vue'
import NaiveBridge from './components/NaiveBridge.vue'
import { themeOverrides } from './theme'
</script>

<template>
  <NConfigProvider
    :theme="darkTheme"
    :theme-overrides="themeOverrides"
    :locale="zhCN"
    :date-locale="dateZhCN"
    preflight-style-disabled
  >
    <NMessageProvider :max="3" placement="top">
      <NDialogProvider>
        <NaiveBridge />
        <AppShell />
      </NDialogProvider>
    </NMessageProvider>
  </NConfigProvider>
</template>
