# 콘솔 프론트 — Vite 빌드 산출물을 nginx로 서빙하고 /api·/uploads 를 mail-api 로 프록시.
# 단일 오리진이라 메일 본문의 추적/수신거부 링크(APP_BASE_URL)도 같은 주소를 쓴다.
FROM node:20-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:1.27-alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
