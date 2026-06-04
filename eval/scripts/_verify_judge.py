"""临时验证：不依赖知识库服务，直接用 SiliconFlow 判分端点跑通 Faithfulness + ResponseRelevancy。
验证 (1) embedding 直发字符串不再 400，(2) Qwen3 关 thinking 后不再 180s 超时。验证完即可删。"""
import sys, time
from pathlib import Path

EVAL_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(EVAL_ROOT / "scripts"))

import run_ragas_eval as rre  # 复用其 build_evaluators + .env 加载逻辑

from ragas import EvaluationDataset, evaluate
from ragas.run_config import RunConfig
from ragas.metrics import Faithfulness, ResponseRelevancy

llm, emb = rre.build_evaluators()
ds = EvaluationDataset.from_list([{
    "user_input": "公司客服的工作时间是几点到几点？",
    "response": "客服的工作时间是每天早上 9 点到晚上 6 点。",
    "retrieved_contexts": ["本公司客户服务中心的服务时间为每日 9:00 至 18:00，节假日除外。"],
}])

t = time.time()
df = evaluate(dataset=ds, metrics=[Faithfulness(), ResponseRelevancy()],
              llm=llm, embeddings=emb,
              run_config=RunConfig(timeout=300, max_workers=4),
              raise_exceptions=True).to_pandas()
print(f"\n[OK] 判分跑通，耗时 {time.time() - t:.1f}s")
print(df[["faithfulness", "answer_relevancy"]].to_string())
