"""IR 检索指标：Hit@k / MRR / Recall@k / Precision@k。

约定：
- ranked_ids: 检索返回的有序 id 列表（doc 级或 chunk 级），按相关性从高到低。
- gold_ids:   该 query 的相关 id 集合（gold）。
- 同一套函数 doc 级、chunk 级都能用，传不同粒度的 id 列表即可。

指标定义（与 docs/RAG评测一条龙实施方案.md §5.1 一致）：
- Hit@k        = 1[ top-k 中至少命中一条 gold ]，全集取均值
- MRR          = 第一个命中 gold 的名次的倒数（top-k 内无命中记 0）
- Recall@k     = |top-k ∩ gold| / |gold|
- Precision@k  = |top-k ∩ gold| / k     （分母固定为 k，标准定义）
"""
from __future__ import annotations

from typing import Sequence


def _dedup_keep_order(ids: Sequence) -> list:
    """去重并保持原始排序（检索结果理论上不应重复，但兜底一下）。"""
    seen = set()
    out = []
    for i in ids:
        if i is None or i in seen:
            continue
        seen.add(i)
        out.append(i)
    return out


def hit_at_k(ranked_ids: Sequence, gold_ids: Sequence, k: int) -> float:
    gold = set(gold_ids)
    if not gold:
        return 0.0
    topk = _dedup_keep_order(ranked_ids)[:k]
    return 1.0 if any(i in gold for i in topk) else 0.0


def mrr(ranked_ids: Sequence, gold_ids: Sequence, k: int | None = None) -> float:
    gold = set(gold_ids)
    if not gold:
        return 0.0
    ranked = _dedup_keep_order(ranked_ids)
    if k is not None:
        ranked = ranked[:k]
    for idx, i in enumerate(ranked, start=1):
        if i in gold:
            return 1.0 / idx
    return 0.0


def recall_at_k(ranked_ids: Sequence, gold_ids: Sequence, k: int) -> float:
    gold = set(gold_ids)
    if not gold:
        return 0.0
    topk = set(_dedup_keep_order(ranked_ids)[:k])
    return len(topk & gold) / len(gold)


def precision_at_k(ranked_ids: Sequence, gold_ids: Sequence, k: int) -> float:
    gold = set(gold_ids)
    if k <= 0 or not gold:
        return 0.0
    topk = _dedup_keep_order(ranked_ids)[:k]
    hit = sum(1 for i in topk if i in gold)
    return hit / k


def evaluate_case(ranked_ids: Sequence, gold_ids: Sequence, ks=(1, 3, 5)) -> dict:
    """对单条 case 计算全部指标，返回扁平 dict，便于聚合。"""
    res: dict[str, float] = {}
    for k in ks:
        res[f"hit@{k}"] = hit_at_k(ranked_ids, gold_ids, k)
        res[f"recall@{k}"] = recall_at_k(ranked_ids, gold_ids, k)
        res[f"precision@{k}"] = precision_at_k(ranked_ids, gold_ids, k)
    res["mrr"] = mrr(ranked_ids, gold_ids, max(ks))
    # 第一个命中的名次（做 error analysis 用；未命中记 0）
    ranked = _dedup_keep_order(ranked_ids)
    gold = set(gold_ids)
    res["first_hit_rank"] = next((idx for idx, i in enumerate(ranked, 1) if i in gold), 0)
    return res


if __name__ == "__main__":
    # 自检：相关项在第 2 名
    ranked = [9, 1, 8, 2, 7]
    gold = [1, 2]
    assert hit_at_k(ranked, gold, 1) == 0.0
    assert hit_at_k(ranked, gold, 3) == 1.0
    assert mrr(ranked, gold) == 0.5            # 1 在第 2 名 -> 1/2
    assert recall_at_k(ranked, gold, 5) == 1.0  # 1、2 都在 top5
    assert precision_at_k(ranked, gold, 5) == 2 / 5
    print("metrics self-check passed")
