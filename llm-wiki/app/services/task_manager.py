# -*- coding: utf-8 -*-
"""后台任务管理：sync / upload 等长耗时操作的后台执行与状态查询

设计：
  - 进程内存 dict 保存任务（保留最近 max_tasks=200 条，满了淘汰最旧）
  - ThreadPoolExecutor(max_workers=3) 后台执行
  - 空间级互斥：同一 space 的写任务（sync/upload）排队串行执行，
    防止 Chroma 同 collection 并发 upsert
  - 任务统一模型：task_id / kind / space / status / created_at /
    finished_at / result / error
"""
from __future__ import annotations

import logging
import secrets
import threading
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any, Callable

logger = logging.getLogger(__name__)


def _now() -> str:
    return datetime.now().strftime("%Y-%m-%d %H:%M:%S")


@dataclass
class TaskRecord:
    """单个后台任务记录（进程内存形态）"""

    task_id: str
    kind: str
    space: str
    status: str  # running / completed / error
    created_at: str
    finished_at: str = ""
    error: str = ""
    result: Any = None
    _fn: Callable[[], Any] | None = field(default=None, repr=False)

    def to_dict(self) -> dict:
        """输出为联调契约 JSON（无 _fn 内部字段）"""
        d: dict[str, Any] = {
            "task_id": self.task_id,
            "kind": self.kind,
            "space": self.space,
            "status": self.status,
            "created_at": self.created_at,
        }
        if self.finished_at:
            d["finished_at"] = self.finished_at
        if self.status == "completed":
            d["result"] = self.result
        elif self.status == "error":
            d["error"] = self.error
        return d


class TaskManager:
    """进程内后台任务管理器（模块级单例/挂在 Services 上均可）"""

    def __init__(self, max_workers: int = 3, max_tasks: int = 200):
        self._max_tasks = max_tasks
        self._tasks: dict[str, TaskRecord] = {}
        self._space_locks: dict[str, threading.Lock] = {}
        self._lock = threading.RLock()
        self._executor = ThreadPoolExecutor(
            max_workers=max_workers, thread_name_prefix="task")

    # ---------- 公开 API ----------
    def submit(self, kind: str, space: str, fn: Callable[[], Any]) -> str:
        """登记任务并提交后台执行，立即返回 task_id（status=running）。

        同一 space 的任务在后台排队（空间级互斥），后发任务等待前一任务完成。
        """
        with self._lock:
            task_id = self._gen_task_id()
            rec = TaskRecord(
                task_id=task_id, kind=kind, space=space,
                status="running", created_at=_now(), _fn=fn,
            )
            self._tasks[task_id] = rec
            self._evict_locked()
        self._executor.submit(self._run, rec)
        logger.info("后台任务已提交: task_id=%s kind=%s space=%s",
                    task_id, kind, space)
        return task_id

    def get(self, task_id: str) -> dict | None:
        """按 task_id 查询任务状态与结果（dict 形态），不存在返回 None"""
        with self._lock:
            rec = self._tasks.get(task_id)
            return rec.to_dict() if rec else None

    # ---------- 内部 ----------
    def _run(self, rec: TaskRecord) -> None:
        """后台执行：先取该 space 互斥锁（等待前序任务完成）再运行 fn。

        正常 → completed + result；抛异常 → error + error 消息。
        """
        lock = self._space_lock(rec.space)
        try:
            with lock:
                result = rec._fn()
        except Exception as e:  # noqa: BLE001
            logger.exception("后台任务执行失败: task_id=%s kind=%s space=%s",
                             rec.task_id, rec.kind, rec.space)
            with self._lock:
                rec.status = "error"
                rec.error = str(e)
                rec.finished_at = _now()
            return
        with self._lock:
            rec.status = "completed"
            rec.result = result
            rec.finished_at = _now()
        logger.info("后台任务完成: task_id=%s kind=%s space=%s",
                    rec.task_id, rec.kind, rec.space)

    def _space_lock(self, space: str) -> threading.Lock:
        """每 space 一把锁（懒创建）"""
        with self._lock:
            lock = self._space_locks.get(space)
            if lock is None:
                lock = self._space_locks[space] = threading.Lock()
            return lock

    def _gen_task_id(self) -> str:
        """生成唯一任务 id：t_时间戳_短随机，如 t_20260907_123456_a1b2c3"""
        while True:
            tid = (f"t_{datetime.now().strftime('%Y%m%d%H%M%S')}_"
                   f"{secrets.token_hex(3)}")
            if tid not in self._tasks:
                return tid

    def _evict_locked(self) -> None:
        """保留最近 max_tasks 条，满了淘汰最旧（调用方须持有 self._lock）"""
        while len(self._tasks) > self._max_tasks:
            oldest = next(iter(self._tasks))
            self._tasks.pop(oldest)
            logger.info("后台任务淘汰最旧: task_id=%s", oldest)


# 模块级实例：进程内共享（路由通过 Services.task_manager 引用同一实例）
task_manager = TaskManager()
