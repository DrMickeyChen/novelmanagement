<script setup>
import { useRouter } from 'vue-router'

const router = useRouter()
</script>

<template>
  <el-result icon="warning" title="404" sub-title="这个页面不存在">
    <template #extra>
      <el-button type="primary" @click="router.replace('/')">回到首页</el-button>
    </template>
  </el-result>
</template>
