# -*- coding: utf-8 -*-
"""后台任务查询路由（sync / upload 统一任务模型）"""
from __future__ import annotations

from fastapi import APIRouter, HTTPException, Request

router = APIRouter(prefix="/tasks", tags=["tasks"])


@router.get("/{task_id}")
def get_task(task_id: str, request: Request) -> dict:
    """查询后台任务状态与结果。

    - 任务不存在 → 404
    - running：created_at
    - completed：result（sync→SyncResult；upload→{path, content_hash, ...}）
    - error：error 消息非空
    """
    task = request.app.state.services.task_manager.get(task_id)
    if task is None:
        raise HTTPException(status_code=404, detail=f"任务不存在: {task_id}")
    return task
