from fastapi import APIRouter

from app.api.v1.routes import admin, auth, dashboard, rppg, sensor, session, stt, system


api_router = APIRouter()
api_router.include_router(auth.router)
api_router.include_router(admin.router)
api_router.include_router(sensor.router)
api_router.include_router(session.router)
api_router.include_router(stt.router)
api_router.include_router(rppg.router)
api_router.include_router(dashboard.router)
api_router.include_router(system.router)
