<script setup>
const endpoint = `${window.location.origin}/api/ai/book-finder/mcp`
const tools = [
  { name: 'searchBooks', purpose: '搜索已上架作品，精确筛选状态、分类与字数' },
  { name: 'getBookDetails', purpose: '批量查询读者可见的书籍详情，最多20本' },
  { name: 'searchBookEvidence', purpose: '检索可见作品的正文证据，返回版本、覆盖范围和检索模式' }
]
</script>
<template>
  <el-alert title="MCP 对外提供三个只读工具，复用找书业务逻辑。内部助手继续使用 Tool Calling；MCP 不替代推荐判断。" :closable="false" type="info" />
  <p>HTTP 接入地址：<el-text tag="code">{{ endpoint }}</el-text></p>
  <p>传输：Streamable HTTP（JSON 响应）；协议版本：2025-03-26。</p>
  <p>通过网关携带平台登录凭证：Authorization: Bearer &lt;access token&gt;。普通读者每次工具调用消耗一次额度，管理员不限日次数。</p>
  <p>客户端先 initialize，再 notifications/initialized，然后 tools/list 和 tools/call。浏览器客户端的 Origin 必须在服务端允许列表内。</p>
  <el-table :data="tools"><el-table-column prop="name" label="工具" width="230" /><el-table-column prop="purpose" label="作用" /></el-table>
  <p>仅支持 stdio 的客户端可运行项目里的 docs/tools/book-finder-mcp-stdio.py，通过环境变量 NOVEL_ACCESS_TOKEN 传入登录凭证；凭证到期需要更新。</p>
</template>
