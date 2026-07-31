/**
 * 前端 SQLite 本地备份工具
 *
 * <p>使用 sql.js 在浏览器中创建内存数据库，作为模型配置和设置的本地备份。
 * 后端 MySQL 不可用时，从本地 SQLite 恢复。</p>
 *
 * @module utils/modelConfigDb
 */
import initSqlJs from "sql.js"

let db: any = null

async function getDb() {
  if (db) return db
  const SQL = await initSqlJs({
    locateFile: (file: string) => {
      return new URL("/sql-wasm.wasm", window.location.href).href
    }
  })
  db = new SQL.Database()
  db.run(`CREATE TABLE IF NOT EXISTS model_config (
    id INTEGER PRIMARY KEY,
    config_json TEXT NOT NULL,
    updated_at INTEGER NOT NULL
  )`)
  db.run(`CREATE TABLE IF NOT EXISTS app_settings (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL,
    updated_at INTEGER NOT NULL
  )`)
  return db
}

/**
 * 保存应用设置到本地 SQLite（如 ollamaUrl、modelType 等）
 */
export async function saveSettings(key: string, value: string) {
  try {
    const d = await getDb()
    d.run("INSERT OR REPLACE INTO app_settings (key, value, updated_at) VALUES (?, ?, ?)",
      [key, value, Date.now()])
  } catch { /* ignore */ }
}

/**
 * 从本地 SQLite 加载应用设置
 */
export async function loadSettings(key: string): Promise<string | null> {
  try {
    const d = await getDb()
    const result = d.exec("SELECT value FROM app_settings WHERE key = ?", [key] as any)
    if (result.length > 0 && result[0].values?.length > 0) {
      return result[0].values[0][0] as string
    }
  } catch { /* ignore */ }
  return null
}

// ... keep existing saveLocalConfigs and loadLocalConfigs

/**
 * 保存模型配置到本地 SQLite
 *
 * <p>先清空旧数据，再批量插入当前配置。</p>
 *
 * @param configs 模型配置数组
 */
export async function saveLocalConfigs(configs: any[]) {
  const d = await getDb()
  d.run("DELETE FROM model_config")
  const stmt = d.prepare(
    "INSERT INTO model_config (id, config_json, updated_at) VALUES (?, ?, ?)",
  )
  for (const c of configs) {
    stmt.run([c.id || 0, JSON.stringify(c), Date.now()])
  }
  stmt.free()
}

/**
 * 从本地 SQLite 加载模型配置
 *
 * <p>读取备份的配置 JSON 并解析返回。</p>
 *
 * @returns 模型配置数组
 */
export async function loadLocalConfigs(): Promise<any[]> {
  try {
    const d = await getDb()
    const result = d.exec("SELECT config_json FROM model_config ORDER BY id")
    if (result.length > 0 && result[0].values) {
      return result[0].values.map((row: any[]) => JSON.parse(row[0] as string))
    }
  } catch {
    // SQLite 加载失败，忽略
  }
  return []
}
