from typing import Any

from fastapi import APIRouter, HTTPException, Request

from app.services.prediction import prediction_service


router = APIRouter()


@router.get("/health")
async def health(request: Request) -> dict[str, Any]:
    runtime = getattr(request.app.state, "backend_runtime", None)
    return {
        "status": "ok",
        "model": prediction_service.status(),
        "v25": runtime.public_status()
        if runtime is not None
        else {"ready": False, "errorCode": "not_initialized"},
    }


@router.get("/ready")
async def readiness(request: Request) -> dict[str, Any]:
    runtime = getattr(request.app.state, "backend_runtime", None)
    status = (
        runtime.public_status()
        if runtime is not None
        else {"ready": False, "errorCode": "not_initialized"}
    )
    if not status["ready"]:
        raise HTTPException(status_code=503, detail=status)
    return status


@router.get("/model/status")
async def model_status() -> dict[str, Any]:
    return prediction_service.status()
