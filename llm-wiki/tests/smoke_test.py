# -*- coding: utf-8 -*-
"""llm-wiki 冒烟测试：建空间→加页→检索→sync→删页 全链路"""
import json
import sys

import httpx

BASE = "http://127.0.0.1:8000/api/v1"
passed = 0
failed = 0


def check(name: str, cond: bool, extra: str = ""):
    global passed, failed
    if cond:
        passed += 1
        print(f"  [PASS] {name} {extra}")
    else:
        failed += 1
        print(f"  [FAIL] {name} {extra}")


def main():
    c = httpx.Client(timeout=60)

    # 1. 加页面（概念 2 个 + 指标 1 个）
    pages = [
        ("concepts/投诉.md",
         "---\ntitle: 投诉\ntype: concept\ntags: [客户, 投诉]\n---\n"
         "投诉指客户对营业厅服务的正式不满反馈，通过电话或柜台渠道登记。\n"
         "投诉单记录在 T_COMPLAINT 表，字段包括投诉编号、客户账号、营业厅、投诉原因、登记时间。\n"
         "投诉处理流程：受理 → 派单 → 处理 → 回访 → 归档。"),
        ("concepts/营业厅.md",
         "---\ntitle: 营业厅\ntype: concept\ntags: [网点, 渠道]\n---\n"
         "营业厅是公司面向客户的线下服务网点，通过厅号 HALL_ID 唯一标识。\n"
         "营业厅信息存储在 T_BUSINESS_HALL 表，包含厅号、厅名、地址、归属区域。"),
        ("metrics/投诉量.md",
         "---\ntitle: 投诉量\ntype: metric\ntags: [指标, 投诉]\n---\n"
         "投诉量 = COUNT(COMPLAINT_ID)，按登记时间统计。\n"
         "查询本月投诉量：SELECT COUNT(*) FROM T_COMPLAINT WHERE REG_TIME >= 月初。\n"
         "按营业厅分组统计用 HALL_ID 关联 T_BUSINESS_HALL。"),
    ]
    for path, content in pages:
        r = c.post(f"{BASE}/spaces/chatsql/pages", json={"path": path, "content": content})
        check(f"创建页面 {path}", r.status_code == 201, f"-> {r.status_code}")

    # 2. 页面列表
    r = c.get(f"{BASE}/spaces/chatsql/pages")
    check("页面列表", r.status_code == 200 and len(r.json()) == 3,
          f"-> {len(r.json())} 页")

    # 3. 语义检索（向量通道）
    r = c.post(f"{BASE}/spaces/chatsql/search", json={"query": "客户对服务不满怎么登记", "top_k": 3})
    body = r.json()
    paths = [h["path"] for h in body]
    check("语义检索命中投诉", r.status_code == 200 and "concepts/投诉.md" in paths,
          f"-> {paths[:2]}")

    # 4. 关键词检索（BM25 通道，命中"投诉量"指标页）
    r = c.post(f"{BASE}/spaces/chatsql/search", json={"query": "投诉量", "top_k": 3})
    body = r.json()
    paths = [h["path"] for h in body]
    check("关键词检索命中投诉量指标", any("metrics/投诉量.md" in p for p in paths),
          f"-> {paths[:2]}")

    # 5. type 过滤检索
    r = c.post(f"{BASE}/spaces/chatsql/search",
               json={"query": "投诉", "top_k": 5, "filters": {"type": "metric"}})
    body = r.json()
    check("type 过滤仅返回 metric", all(h["type"] == "metric" for h in body) and len(body) > 0,
          f"-> {[h['path'] for h in body]}")

    # 6. sync（无变化应 skipped=3）
    r = c.post(f"{BASE}/spaces/chatsql/pages/sync")
    body = r.json()
    check("sync 无变化全部跳过", body.get("skipped") == 3 and not body.get("errors"),
          f"-> skipped={body.get('skipped')}")

    # 7. 磁盘直接加一个文件再 sync（模拟人工手工放文件）
    import os
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    wiki_dir = os.path.join(root, "data", "wiki", "chatsql")
    os.makedirs(os.path.join(wiki_dir, "faq"), exist_ok=True)
    with open(os.path.join(wiki_dir, "faq", "投诉状态.md"), "w", encoding="utf-8") as f:
        f.write("---\ntitle: 投诉状态\ntype: faq\n---\n投诉状态枚举：1=待处理 2=处理中 3=已办结。")
    r = c.post(f"{BASE}/spaces/chatsql/pages/sync")
    body = r.json()
    check("sync 发现磁盘新文件", len(body.get("added")) == 1,
          f"-> added={body.get('added')}")
    r = c.get(f"{BASE}/spaces/chatsql/pages")
    check("页面数=4", len(r.json()) == 4, f"-> {len(r.json())}")

    # 8. 删除一个页面（向量联动）
    r = c.delete(f"{BASE}/spaces/chatsql/pages/faq/投诉状态.md")
    check("删除页面", r.status_code == 204, f"-> {r.status_code}")
    r = c.get(f"{BASE}/spaces/chatsql/pages")
    check("删除后页面数=3", len(r.json()) == 3, f"-> {len(r.json())}")
    # 删除后检索不应再命中
    r = c.post(f"{BASE}/spaces/chatsql/search", json={"query": "投诉状态枚举", "top_k": 3})
    paths = [h["path"] for h in r.json()]
    check("删除后不再命中已删页", not any("faq/投诉状态.md" in p for p in paths),
          f"-> {paths}")

    # 9. GET 单页内容回读
    r = c.get(f"{BASE}/spaces/chatsql/pages/concepts/投诉.md")
    b = r.json()
    check("单页回读", r.status_code == 200 and b["title"] == "投诉" and b["type"] == "concept"
          and "T_COMPLAINT" in b["content"], f"-> title={b.get('title')}")

    # 10. 更新页面（修改内容后 hash/向量更新）
    r = c.put(f"{BASE}/spaces/chatsql/pages/concepts/投诉.md",
              json={"path": "concepts/投诉.md",
                    "content": "---\ntitle: 投诉\ntype: concept\ntags: [客户, 投诉]\n---\n"
                               "投诉指客户对营业厅服务的正式不满反馈。已更新 2026。\n登记在投诉登记表。"})
    check("更新页面", r.status_code == 200, f"-> {r.status_code}")
    r = c.get(f"{BASE}/spaces/chatsql/pages/concepts/投诉.md")
    check("更新后内容生效", "已更新 2026" in r.json()["content"])

    print(f"\n===== 冒烟结果: PASS={passed} FAIL={failed} =====")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
