<script setup>
/**
 * 根组件。
 *
 * 这里只放一个 <router-view />，真正的页面框架在 layouts/BasicLayout.vue 里 ——
 * 因为登录页【不需要】顶部导航（它是个独立的全屏页面），
 * 所以布局不能写在根组件上，必须由路由决定用哪个 layout。
 */
</script>

<template>
  <router-view />
</template>
