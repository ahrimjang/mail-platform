# 콘솔 프론트 — Vite 빌드 산출물을 nginx로 서빙하고 /api·/uploads 를 mail-api 로 프록시.
# 단일 오리진이라 메일 본문의 추적/수신거부 링크(APP_BASE_URL)도 같은 주소를 쓴다.
FROM node:20-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
# 구글 로그인 버튼은 빌드 시점 값으로 켜진다 — 미설정이면 버튼만 안 그려짐
ARG VITE_GOOGLE_CLIENT_ID=
ENV VITE_GOOGLE_CLIENT_ID=$VITE_GOOGLE_CLIENT_ID
RUN npm run build

FROM nginx:1.27-alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY nginx-common.conf /etc/nginx/snippets/outpace.conf
# certs 는 마운트 자리 — 비어 있으면 include 글롭이 매치 없이 지나가 80만 뜬다
RUN mkdir -p /etc/nginx/certs
EXPOSE 80 443
