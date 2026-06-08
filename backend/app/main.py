from fastapi import FastAPI, Depends, HTTPException, Header
from fastapi.middleware.cors import CORSMiddleware
import httpx
import os

app = FastAPI(title="Backend API", version="1.0.0")

# CORS 설정 (모바일 앱 + React 프론트에서 접근 허용)
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],  # 배포 시 실제 도메인으로 교체
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

LLM_SERVER_URL = os.getenv("LLM_SERVER_URL", "http://localhost:8001")
LLM_API_KEY = os.getenv("LLM_API_KEY", "")


# ───────────────────────────
# 헬스체크
# ───────────────────────────
@app.get("/health")
async def health():
    return {"status": "ok"}


# ───────────────────────────
# LLM 서버 호출 예시
# ───────────────────────────
@app.post("/api/llm/chat")
async def llm_chat(body: dict):
    """백엔드에서 GPU 서버(LLM)로 요청을 중계합니다."""
    async with httpx.AsyncClient(timeout=60.0) as client:
        try:
            response = await client.post(
                f"{LLM_SERVER_URL}/generate",
                json=body,
                headers={"x-api-key": LLM_API_KEY},
            )
            response.raise_for_status()
            return response.json()
        except httpx.ConnectError:
            raise HTTPException(status_code=503, detail="LLM 서버에 연결할 수 없습니다")
        except httpx.HTTPStatusError as e:
            raise HTTPException(status_code=e.response.status_code, detail=str(e))


# ───────────────────────────
# 예시 라우터 (나중에 분리 가능)
# ───────────────────────────
@app.get("/api/users")
async def get_users():
    # TODO: DB 연결 후 실제 구현
    return [{"id": 1, "name": "테스트 유저"}]
