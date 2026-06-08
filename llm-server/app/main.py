from fastapi import FastAPI, HTTPException, Header
from pydantic import BaseModel
import os

app = FastAPI(title="LLM Server", version="1.0.0")

LLM_API_KEY = os.getenv("LLM_API_KEY", "")   # 백엔드가 보내는 인증 키
OPENAI_API_KEY = os.getenv("OPENAI_API_KEY", "")


# ── 인증 의존성 ─────────────────────────────────────────
async def verify_api_key(x_api_key: str = Header(...)):
    """백엔드 서버만 호출 가능하도록 API Key 검증"""
    if x_api_key != LLM_API_KEY:
        raise HTTPException(status_code=403, detail="Invalid API key")


# ── 요청/응답 스키마 ────────────────────────────────────
class GenerateRequest(BaseModel):
    prompt: str
    max_tokens: int = 512
    temperature: float = 0.7


class GenerateResponse(BaseModel):
    text: str
    model: str


# ── 헬스체크 ────────────────────────────────────────────
@app.get("/health")
async def health():
    return {"status": "ok"}


# ── 생성 엔드포인트 ─────────────────────────────────────
@app.post("/generate", response_model=GenerateResponse)
async def generate(req: GenerateRequest, _=__import__('fastapi').Depends(verify_api_key)):
    """
    외부 API 방식 (OpenAI) — 기본 활성화
    로컬 모델 방식은 아래 주석 참고
    """
    # ── 외부 API 방식 (OpenAI) ──────────────────────────
    from openai import AsyncOpenAI
    client = AsyncOpenAI(api_key=OPENAI_API_KEY)

    response = await client.chat.completions.create(
        model="gpt-4o-mini",
        messages=[{"role": "user", "content": req.prompt}],
        max_tokens=req.max_tokens,
        temperature=req.temperature,
    )
    return GenerateResponse(
        text=response.choices[0].message.content,
        model=response.model,
    )

    # ── 로컬 모델 방식 (vLLM) — 위 코드 대신 교체 ─────────
    # from vllm import AsyncLLMEngine, SamplingParams
    # ... (모델 로드 후 generate 호출)

    # ── 로컬 모델 방식 (llama.cpp) ─────────────────────
    # from llama_cpp import Llama
    # llm = Llama(model_path="/app/models/your-model.gguf")
    # output = llm(req.prompt, max_tokens=req.max_tokens)
    # return GenerateResponse(text=output["choices"][0]["text"], model="llama-local")
