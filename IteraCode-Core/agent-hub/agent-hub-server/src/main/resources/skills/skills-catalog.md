---
name: skills-catalog
displayName: 技能目录
description: 自动化研发智能体系统当前可用技能目录（阶段一：需求拆解 → 推理 → 审核）
---

# AGENT-HUB 技能目录

## 目录结构

```
skills/
├── design/                     # 分析与设计技能
│   ├── requirement-analysis.md  需求拆解
│   └── architecture-design.md   架构设计推理
└── skills-catalog.md            本目录文件
```

## 技能总览

### design（分析与设计）

| 序号 | 技能名称 | 显示名称 | 模型 | 循环轮数 | 输出键 |
|------|----------|----------|------|----------|--------|
| 1 | requirement-analysis | 需求拆解智能体 | gemma2:2b | 4轮 | decomposition_result |
| 2 | architecture-design | 架构设计推理智能体 | gemma2:2b/qwen3:4b | 5轮 | reasoning_result |

## 技能工作流

```
需求输入
  │
  ▼
┌──────────────────────────────────────────────────────┐
│ [1. requirement-analysis] 需求拆解（多轮循环）         │
└──────────┬───────────────────────────────────────────┘
           │
           ▼
┌──────────────────────────────────────────────────────┐
│ [2. architecture-design] 多模型并行推理（多轮循环）    │
└──────────┬───────────────────────────────────────────┘
           │
           ▼
      [人工审核] ◄── 中断点
           │
   ┌───────┼──────────┐
   │       │          │
   ▼       ▼          ▼
APPROVED  SENT_BACK  TERMINATED
  (完成)  (回到R1)    (终止)
```

## 技能选择指南

### 按分类选择

| 分类 | 目录 | 技能 |
|------|------|------|
| 分析与设计 | skills/design/ | requirement-analysis, architecture-design |

### 按任务类型选择

| 任务类型 | 技能链 |
|----------|--------|
| 需求拆解与评审 | requirement-analysis → architecture-design → 人工审核 |
