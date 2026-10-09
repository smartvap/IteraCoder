---
name: skills-catalog
displayName: 技能目录
description: 自动化研发智能体系统全部可用技能的总目录，供 Agent 动态发现和选择技能
---

# AGENT-HUB 技能目录

## 目录结构

技能按业务模块分层：`workflow/` 属 workflow 模块（研发智能体工作流），`rc/` 属 rcagent 模块（查数归因工作流）。

```
skills/
├── workflow/                     # workflow 模块技能（研发智能体工作流）
│   ├── design/                   # 分析与设计技能
│   │   ├── requirement-analysis.md  需求拆解
│   │   ├── architecture-design.md   架构设计推理
│   │   └── api-contract-design.md   API 契约设计
│   ├── dev/                      # 研发实现技能
│   │   ├── code-generation.md       代码生成
│   │   └── code-repair.md           代码修复
│   └── ops/                      # 运维/验证技能
│       └── sandbox-testing.md       沙箱测试验证
├── rc/                           # rcagent 模块技能（RCAgent 查数归因工作流）
│   ├── rc-intent.md                 意图识别
│   ├── rc-sql-plan.md               SQL 规划
│   ├── rc-sql-generate.md           SQL 生成
│   ├── rc-answer.md                 知识问答
│   ├── rc-result-check.md           结果校验与摘要
│   ├── rc-attribution-explain.md    归因解释
│   ├── rc-query-answer.md           查数结论生成
│   ├── rc-cross-source-join-plan.md        跨源关联规划（module-003）
│   └── rc-cross-source-join-generate.md    跨源逐源 SQL 生成（module-003）
└── skills-catalog.md             # 本目录文件（非技能）
```

> 技能分类取自文件路径：`SkillLoader` 递归扫描 `classpath:skills`，以 skills 之后目录部分的**叶子目录名**作为 category
> （如 `skills/workflow/design/xxx.md` → `design`，`skills/rc/xxx.md` → `rc`）；直接位于 `skills/` 根目录下的文件（本目录文件）会被跳过。

## 技能总览

### workflow 模块（研发智能体工作流）

| 序号 | 技能名称 | 显示名称 | 类别(category) | 加载位置(节点) | 输出键 |
|------|----------|----------|----------------|----------------|--------|
| 1 | requirement-analysis | 需求拆解智能体 | design | 需求拆解节点 | decomposition_result |
| 2 | architecture-design | 架构设计推理智能体 | design | 并行推理（模型0） | reasoning_result |
| 3 | api-contract-design | API 契约设计智能体 | design | 并行推理（模型1） | reasoning_result |
| 4 | code-generation | 代码生成智能体 | dev | 代码生成节点 | generated_code |
| 5 | sandbox-testing | 沙箱测试验证智能体 | ops | 沙箱验证节点 | harness_result |
| 6 | code-repair | 代码修复智能体 | dev | 修复循环节点 | generated_code |

### rcagent 模块（RCAgent 查数归因工作流）

| 序号 | 技能名称 | 显示名称 | 类别(category) | 加载位置(节点) | 输出键 |
|------|----------|----------|----------------|----------------|--------|
| 7 | rc-intent | RCAgent 意图识别 | rc | rcagent_intent（图谱暂未接入） | intent_result |
| 8 | rc-sql-plan | RCAgent SQL 规划 | rc | rcagent_sql_plan（图谱暂未接入） | sql_plan |
| 9 | rc-sql-generate | RCAgent SQL 生成 | rc | SQL 生成节点（rcagent_sql_generate） | sql_generate |
| 10 | rc-answer | RCAgent 知识问答 | rc | 问答节点（rcagent_answer） | answer |
| 11 | rc-result-check | RCAgent 结果校验与摘要 | rc | rcagent_result_check（图谱暂未接入） | result_summary |
| 12 | rc-attribution-explain | RCAgent 归因解释 | rc | rcagent_attribution（图谱暂未接入） | attribution_result |
| 13 | rc-query-answer | RCAgent 查数结论生成 | rc | 查数结论节点（rcagent_query_answer） | query_answer |
| 14 | rc-cross-source-join-plan | RCAgent 跨源关联规划 | rc | 跨源规划节点（rcagent_cross_source_plan） | join_plan |
| 15 | rc-cross-source-join-generate | RCAgent 跨源逐源 SQL 生成 | rc | 跨源生成节点（rcagent_cross_source_generate） | source_query_plans |

> 合计 15 个技能：workflow 模块 6 个（design/dev/ops），rcagent 模块 9 个（rc）。
> 其中 rcagent 侧当前 `rc-sql-generate`、`rc-answer`、`rc-query-answer`、`rc-cross-source-join-plan`、
> `rc-cross-source-join-generate` 由 `RcaWorkflowGraphConfig` 读取并注入节点；
> 其余 4 个 rc 技能为已定义待接入（节点内联兜底，接口约定确定前沿用预留）。

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

- 所有技能文件由 `SkillLoader` 从 `classpath:skills` 递归扫描**动态加载**，新增/删除文件不影响启动；
- 分类由文件路径的叶子目录名决定（`skills/workflow/design` → `design`，`skills/rc` → `rc`）；
- 实际工作流在 `RdWorkflowGraphConfig` 中通过 `getInstruction(name)` 加载对应技能：
  - `requirement-analysis` → 需求拆解 Agent
  - `architecture-design` / `api-contract-design` → 并行推理 Agents（多模型差异化）
  - `code-generation` → 代码生成 Agent
  - `sandbox-testing` → 沙箱验证 Agent
  - `code-repair` → 代码修复 Agent
- RCAgent 工作流在 `RcaWorkflowGraphConfig` 中读取 `rc/` 技能：
  - `rc-sql-generate` → SQL 生成节点
  - `rc-answer` → 问答节点
  - `rc-query-answer` → 查数结论节点
- 技能文件缺失时自动回退到内置兜底指令（`RdWorkflowGraphConfig` / `RcaWorkflowGraphConfig` 中定义），不影响启动。

## 技能链（任务场景）

| 任务类型 | 技能链 |
|----------|--------|
| 新功能开发 | requirement-analysis → architecture-design / api-contract-design → code-generation → sandbox-testing |
| Bug 修复 | requirement-analysis → code-generation → sandbox-testing → code-repair |
| 代码重构 | architecture-design → code-generation → sandbox-testing → code-repair |
| API 开发 | requirement-analysis → architecture-design / api-contract-design → code-generation → sandbox-testing |
