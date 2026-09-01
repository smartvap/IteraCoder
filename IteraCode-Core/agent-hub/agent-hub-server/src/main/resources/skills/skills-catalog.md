---
name: skills-catalog
displayName: 技能目录
description: 自动化研发智能体系统全部可用技能的总目录，供 Agent 动态发现和选择技能
---

# AGENT-HUB 技能目录

## 目录结构

```
skills/
├── design/                     # 分析与设计技能
│   ├── requirement-analysis.md  需求拆解
│   ├── architecture-design.md   架构设计推理
│   └── api-contract-design.md   API 契约设计
├── dev/                        # 研发实现技能
│   ├── code-generation.md       代码生成
│   └── code-repair.md           代码修复
├── ops/                        # 运维/验证技能
│   └── sandbox-testing.md       沙箱测试验证
└── skills-catalog.md            本目录文件
```


## 技能总览

### design（分析与设计）

| 序号 | 技能名称 | 显示名称 | 加载位置 | 输出键 |
|------|----------|----------|----------|--------|
| 1 | requirement-analysis | 需求拆解智能体 | 需求拆解节点 | decomposition_result |
| 2 | architecture-design | 架构设计推理智能体 | 并行推理（模型0） | {modelKey}_result |
| 3 | api-contract-design | API 契约设计智能体 | 并行推理（模型1） | {modelKey}_result |

### dev（研发实现）

| 序号 | 技能名称 | 显示名称 | 加载位置 | 输出键 |
|------|----------|----------|----------|--------|
| 4 | code-generation | 代码生成智能体 | 代码生成节点 | generated_code |
| 5 | code-repair | 代码修复智能体 | 修复循环节点 | generated_code |

### ops（运维/验证）

| 序号 | 技能名称 | 显示名称 | 加载位置 | 输出键 |
|------|----------|----------|----------|--------|
| 6 | sandbox-testing | 沙箱测试验证智能体 | 沙箱验证节点 | harness_result |

## 技能工作流（循环模式）

每个技能内部都采用多轮聚焦循环，从"一次成型"改为"逐层产出+自检修正"。

```
需求输入
  │
  ▼
┌─────────────────────────────────────────────────────────┐
│ [1. requirement-analysis] 需求拆解（多轮循环）             │
└──────────┬──────────────────────────────────────────────┘
           │
           ▼
┌─────────────────────────────────────────────────────────┐
│ [2. architecture-design] ─┐                             │
│  并行推理（多模型差异化）    ├─→ 融合结果                  │
│ [3. api-contract-design] ─┘                             │
└──────────┬──────────────────────────────────────────────┘
           │
           ▼
      [人工审核] ◄── Human-in-the-Loop 中断点
           │
  ┌────────┼────────┬──────────────┐
  │        │        │              │
  ▼        ▼        ▼              ▼
APPROVED  SENT_BACK  TERMINATED   MODIFY
  │        │        │              │
  │        └──► 回到 R1 ──────────┘
  │
  ▼
┌─────────────────────────────────────────────────────────┐
│ [4. code-generation] 代码生成（多轮循环）                  │
└──────────┬──────────────────────────────────────────────┘
           │
           ▼
┌─────────────────────────────────────────────────────────┐
│ [5. sandbox-testing] 沙箱验证                            │
│  增量编译 → 运行时 → 测试汇总                             │
└──────────┬──────────────────────────────────────────────┘
     ┌─────┴─────┐
     ▼           ▼
   通过        失败
     │           │
     │           ▼
     │    ┌──────────────────────────────────────────┐
     │    │ [6. code-repair] 代码修复（多轮自适应循环）  │
     │    └──────────┬───────────────────────────────┘
     │               │
     │               └──► 回到 sandbox-testing
     ▼
   完成
```

### 循环设计原则

| 原则 | 说明 |
|------|------|
| 单轮聚焦 | 每轮只盯一个维度，不跨维度输出 |
| 输出驱动 | 上轮输出是下轮输入，形成信息链 |
| 轮内自检 | 每轮末尾有 checklist，不合格不进入下一轮 |
| 允许回溯 | 后轮发现前轮错误，可回溯修正 |
| 提前终止 | 简单场景允许跳过不必要的轮次 |

## 技能加载说明

- 所有技能文件由 `SkillLoader` 从 `classpath:skills/**/*.md` **动态扫描加载**，新增/删除文件不影响启动；
- 实际工作流在 `RdWorkflowGraphConfig` 中通过 `getInstruction(name)` 加载对应技能：
  - `requirement-analysis` → 需求拆解 Agent
  - `architecture-design` / `api-contract-design` → 并行推理 Agents（多模型差异化）
  - `code-generation` → 代码生成 Agent
  - `sandbox-testing` → 沙箱验证 Agent
  - `code-repair` → 代码修复 Agent
- 技能文件缺失时自动回退到内置兜底指令（`RdWorkflowGraphConfig` 中定义），不影响启动。

## 技能链（任务场景）

| 任务类型 | 技能链 |
|----------|--------|
| 新功能开发 | requirement-analysis → architecture-design / api-contract-design → code-generation → sandbox-testing |
| Bug 修复 | requirement-analysis → code-generation → sandbox-testing → code-repair |
| 代码重构 | architecture-design → code-generation → sandbox-testing → code-repair |
| API 开发 | requirement-analysis → architecture-design / api-contract-design → code-generation → sandbox-testing |
