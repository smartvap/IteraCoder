/**
 * 数据存储路由——根据登录状态选择本地 DB 或后端 API
 *
 * <p>未登录: Electron 本地 SQLite</p>
 * <p>已登录: 后端 MySQL API</p>
 */
import initSqlJs from "sql.js"

let db: any = null

async function getLocalDb() {
  if (db) return db
  const SQL = await initSqlJs({
    locateFile: () => new URL("/sql-wasm.wasm", window.location.href).href
  })
  db = new SQL.Database()
  db.run(`CREATE TABLE IF NOT EXISTS token_usage (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    model_name TEXT NOT NULL,
    prompt_tokens INTEGER DEFAULT 0,
    completion_tokens INTEGER DEFAULT 0,
    total_duration_ms INTEGER DEFAULT 0,
    request_time INTEGER NOT NULL
  )`)
  return db
}

function isLoggedIn(): boolean {
  const token = localStorage.getItem("token")
  return !!token && token !== "guest-token"
}

/** 保存 token 用量 */
export async function saveTokenUsage(data: {
  modelName: string
  promptTokens: number
  completionTokens: number
  totalDurationMs: number
}) {
  if (isLoggedIn()) {
    // 已登录 → 后端 MySQL（通过 tokenUsageService 自动记录，无需前端调用）
    return
  }
  // 未登录 → 本地 SQLite
  const d = await getLocalDb()
  d.run("INSERT INTO token_usage (model_name, prompt_tokens, completion_tokens, total_duration_ms, request_time) VALUES (?, ?, ?, ?, ?)",
    [data.modelName, data.promptTokens, data.completionTokens, data.totalDurationMs, Date.now()])
}

/** 获取今日 token 用量 */
export async function getTodayTokenUsage(): Promise<{
  totalRequests: number
  totalPromptTokens: number
  totalCompletionTokens: number
  totalTokens: number
}> {
  if (isLoggedIn()) {
    // 已登录 → 后端 API
    const res = await fetch("/api/v1/stats/token/usage")
    if (res.ok) return res.json()
  }
  // 未登录 → 本地 SQLite
  const d = await getLocalDb()
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  const ts = today.getTime()
  const result = d.exec("SELECT COUNT(*) as cnt, COALESCE(SUM(prompt_tokens),0), COALESCE(SUM(completion_tokens),0) FROM token_usage WHERE request_time >= ?", [ts])
  if (result.length > 0 && result[0].values?.length > 0) {
    const [cnt, pt, ct] = result[0].values[0]
    return { totalRequests: cnt, totalPromptTokens: pt, totalCompletionTokens: ct, totalTokens: pt + ct }
  }
  return { totalRequests: 0, totalPromptTokens: 0, totalCompletionTokens: 0, totalTokens: 0 }
}

/** 同步本地数据到后端 */
export async function syncLocalToBackend() {
  if (!isLoggedIn()) return
  const d = await getLocalDb()
  const result = d.exec("SELECT model_name, prompt_tokens, completion_tokens, total_duration_ms FROM token_usage")
  if (result.length === 0 || !result[0].values) return
  for (const row of result[0].values) {
    try {
      await fetch("/api/v1/stats/token/sync", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          modelName: row[0],
          promptTokens: row[1],
          completionTokens: row[2],
          totalDurationMs: row[3],
        })
      })
    } catch { /* ignore */ }
  }
  d.run("DELETE FROM token_usage")
}
