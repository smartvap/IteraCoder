<template>
  <div class="stats-page">
    <h2 class="stats-title">Token 用量统计</h2>

    <div class="stats-cards">
      <div class="stat-card">
        <span class="stat-label">今日请求数</span>
        <span class="stat-value">{{ todayStats.totalRequests }}</span>
      </div>
      <div class="stat-card">
        <span class="stat-label">输入 Token</span>
        <span class="stat-value">{{ todayStats.totalPromptTokens.toLocaleString() }}</span>
      </div>
      <div class="stat-card">
        <span class="stat-label">输出 Token</span>
        <span class="stat-value">{{ todayStats.totalCompletionTokens.toLocaleString() }}</span>
      </div>
      <div class="stat-card">
        <span class="stat-label">总计 Token</span>
        <span class="stat-value">{{ todayStats.totalTokens.toLocaleString() }}</span>
      </div>
    </div>

    <!-- 按模型统计 -->
    <h3 class="section-title">按模型统计</h3>
    <el-table :data="modelStats" stripe size="small" style="width: 100%; margin-bottom: 24px;">
      <el-table-column prop="modelName" label="模型" width="200" />
      <el-table-column prop="requests" label="请求数" width="110" />
      <el-table-column prop="promptTokens" label="输入 Token" width="130">
        <template #default="{ row }">{{ row.promptTokens.toLocaleString() }}</template>
      </el-table-column>
      <el-table-column prop="completionTokens" label="输出 Token" width="130">
        <template #default="{ row }">{{ row.completionTokens.toLocaleString() }}</template>
      </el-table-column>
      <el-table-column label="总计" width="120">
        <template #default="{ row }">{{ (row.promptTokens + row.completionTokens).toLocaleString() }}</template>
      </el-table-column>
      <el-table-column prop="totalDurationMs" label="耗时(ms)" width="110" />
    </el-table>

    <h3 class="section-title">请求明细</h3>
    <el-table :data="detailList" stripe size="small" style="width: 100%" v-loading="loading">
      <el-table-column prop="requestTime" label="时间" width="180">
        <template #default="{ row }">{{ formatTime(row.requestTime) }}</template>
      </el-table-column>
      <el-table-column prop="modelName" label="模型" width="160" />
      <el-table-column prop="ipAddress" label="IP" width="150" />
      <el-table-column prop="promptTokens" label="输入 Token" width="110" />
      <el-table-column prop="completionTokens" label="输出 Token" width="110" />
      <el-table-column label="总 Token" width="100">
        <template #default="{ row }">{{ row.promptTokens + row.completionTokens }}</template>
      </el-table-column>
      <el-table-column prop="totalDurationMs" label="耗时(ms)" width="100" />
      <el-table-column prop="status" label="状态" width="80">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'danger'" size="small">
            {{ row.status === 1 ? '成功' : '失败' }}
          </el-tag>
        </template>
      </el-table-column>
    </el-table>

    <div class="pagination-wrap">
      <el-pagination
        v-model:current-page="query.page"
        v-model:page-size="query.pageSize"
        :total="total"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next"
        @change="fetchDetail"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from "vue"
import { getTodayTokenUsage, getTokenUsageDetail } from "@/api/ChatApi"
import service from "@/http"

const todayStats = reactive({
  totalRequests: 0,
  totalPromptTokens: 0,
  totalCompletionTokens: 0,
  totalTokens: 0,
})

const loading = ref(false)
const detailList = ref<any[]>([])
const modelStats = ref<any[]>([])
const total = ref(0)
const query = reactive({ page: 1, pageSize: 20 })

async function fetchTodayStats() {
  try {
    const res: any = await getTodayTokenUsage()
    Object.assign(todayStats, res)
  } catch {}
}

async function fetchModelStats() {
  try {
    const res: any = await service.get("/stats/token/by-model")
    modelStats.value = res || []
  } catch {}
}

async function fetchDetail() {
  loading.value = true
  try {
    const res: any = await getTokenUsageDetail({ page: query.page, pageSize: query.pageSize })
    detailList.value = res.records || []
    total.value = res.total || 0
  } catch {} finally {
    loading.value = false
  }
}

function formatTime(t: string) {
  if (!t) return ""
  return new Date(t).toLocaleString("zh-CN")
}

onMounted(() => {
  fetchTodayStats()
  fetchModelStats()
  fetchDetail()
})
</script>

<style scoped>
.stats-page {
  max-width: 1200px; margin: 0 auto; padding: 24px 32px;
  height: 100%; overflow-y: auto; box-sizing: border-box;
}
.stats-title { font-size: 22px; font-weight: 700; margin: 0 0 20px; color: #1f2937; }
.stats-cards { display: flex; gap: 16px; margin-bottom: 32px; }
.stat-card {
  flex: 1; background: #fff; border: 1px solid #e5e7eb;
  border-radius: 10px; padding: 16px 20px;
}
.stat-label { font-size: 13px; color: #9ca3af; display: block; margin-bottom: 6px; }
.stat-value { font-size: 24px; font-weight: 700; color: #1f2937; }
.section-title { font-size: 16px; font-weight: 600; margin: 0 0 12px; color: #374151; }
.pagination-wrap { margin-top: 16px; display: flex; justify-content: flex-end; }
</style>
