<script setup>
import { useRouter } from 'vue-router'

const router = useRouter()
</script>

<template>
  <el-result icon="warning" title="404" sub-title="创作中心里没有这个页面">
    <template #extra>
      <el-button type="primary" @click="router.replace('/')">回到工作台</el-button>
    </template>
  </el-result>
</template>
