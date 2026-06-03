"""知识库服务 HTTP 客户端。

两种鉴权模式（由 EVAL_AUTH_MODE 控制）：
- direct（默认）：直连 knowledge 服务，带 X-User-Id / X-Tenant-Id
                  （下游 GatewayHeaderAuthenticationFilter 信任网关身份头），本机评测最省事。
- gateway：先 POST /api/user/login 拿 JWT，带 Authorization: Bearer 打网关，更接近真实链路。

返回体统一是 Result<T> = {code, message, data}，code==200 为成功。
search 的 dense/sparse/rerank/recallTopN 覆盖参数：Java 侧加上对应字段后即生效；
未加之前这些字段会被 Spring 默认 Jackson 忽略（不报错），服务退回全局配置默认值。
"""
from __future__ import annotations

import json
import os

import requests

SUCCESS_CODE = 200


class ApiError(RuntimeError):
    pass


class KnowledgeClient:
    def __init__(
        self,
        base_url: str | None = None,
        mode: str | None = None,
        user_id: str | None = None,
        tenant_id: str | None = None,
        gateway_url: str | None = None,
        username: str | None = None,
        password: str | None = None,
        timeout: int = 600,
    ):
        self.mode = (mode or os.getenv("EVAL_AUTH_MODE", "direct")).strip()
        self.base_url = (base_url or os.getenv("KNOWLEDGE_BASE_URL", "http://localhost:8082")).rstrip("/")
        self.gateway_url = (gateway_url or os.getenv("GATEWAY_BASE_URL", "http://localhost:8080")).rstrip("/")
        self.user_id = user_id or os.getenv("X_USER_ID", "1")
        self.tenant_id = tenant_id or os.getenv("X_TENANT_ID", "1")
        self.username = username or os.getenv("EVAL_USERNAME")
        self.password = password or os.getenv("EVAL_PASSWORD")
        self.timeout = timeout
        self._token: str | None = None
        self._session = requests.Session()
        if self.mode == "gateway":
            self._login()

    @property
    def _root(self) -> str:
        return self.gateway_url if self.mode == "gateway" else self.base_url

    def _login(self) -> None:
        if not self.username or not self.password:
            raise ApiError("gateway 模式需要 EVAL_USERNAME / EVAL_PASSWORD")
        url = f"{self.gateway_url}/api/user/login"
        resp = self._session.post(url, json={"username": self.username, "password": self.password}, timeout=self.timeout)
        data = self._unwrap(resp)
        self._token = data["token"]

    def _headers(self) -> dict:
        if self.mode == "gateway":
            return {"Authorization": f"Bearer {self._token}"}
        return {"X-User-Id": str(self.user_id), "X-Tenant-Id": str(self.tenant_id)}

    def _unwrap(self, resp: requests.Response):
        try:
            body = resp.json()
        except ValueError:
            raise ApiError(f"非 JSON 响应 HTTP {resp.status_code}: {resp.text[:200]}")
        if isinstance(body, dict) and "code" in body:
            if body["code"] != SUCCESS_CODE:
                raise ApiError(f"接口失败 code={body['code']} msg={body.get('message')}")
            return body.get("data")
        return body

    def _get(self, path: str):
        return self._unwrap(self._session.get(f"{self._root}{path}", headers=self._headers(), timeout=self.timeout))

    def _post(self, path: str, json_body: dict):
        return self._unwrap(self._session.post(f"{self._root}{path}", headers=self._headers(), json=json_body, timeout=self.timeout))

    # ---------------- 业务接口 ----------------
    def list_bases(self) -> list:
        return self._get("/api/knowledge/bases") or []

    def list_documents(self, kb_id) -> list:
        return self._get(f"/api/knowledge/bases/{kb_id}/documents") or []

    def list_chunks(self, kb_id, doc_id) -> list:
        return self._get(f"/api/knowledge/bases/{kb_id}/documents/{doc_id}/chunks") or []

    def search(self, kb_id, query, top_k=5, *, dense=None, sparse=None, rerank=None, recall_top_n=None) -> list:
        body: dict = {"query": query, "topK": top_k}
        _put_if(body, "denseEnabled", dense)
        _put_if(body, "sparseEnabled", sparse)
        _put_if(body, "rerankEnabled", rerank)
        _put_if(body, "recallTopN", recall_top_n)
        return self._post(f"/api/knowledge/bases/{kb_id}/search", body) or []

    def ask(self, kb_id, query, top_k=None, *, dense=None, sparse=None, rerank=None, recall_top_n=None) -> dict:
        body: dict = {"query": query}
        _put_if(body, "topK", top_k)
        _put_if(body, "denseEnabled", dense)
        _put_if(body, "sparseEnabled", sparse)
        _put_if(body, "rerankEnabled", rerank)
        _put_if(body, "recallTopN", recall_top_n)
        return self._post(f"/api/knowledge/bases/{kb_id}/ask", body) or {}


def _put_if(d: dict, key: str, value) -> None:
    if value is not None:
        d[key] = value


def kb_name_to_id() -> dict:
    raw = os.getenv("KB_NAME_TO_ID", "{}")
    try:
        return {str(k): int(v) for k, v in json.loads(raw).items()}
    except (ValueError, TypeError):
        return {}
