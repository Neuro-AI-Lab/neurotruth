# ── 1단계: 빌드 ──────────────────────────
FROM node:20-alpine AS builder

WORKDIR /app

COPY package*.json ./
RUN npm install

COPY . .
RUN npm run build

# ── 2단계: 서빙 (nginx) ──────────────────
FROM nginx:alpine

# 빌드 결과물 복사
COPY --from=builder /app/build /usr/share/nginx/html

# nginx 기본 설정 교체 (React Router 지원)
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 3000

CMD ["nginx", "-g", "daemon off;"]
