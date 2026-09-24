# syntax=docker/dockerfile:1.7
#
# The dashboard as static files behind nginx. The API and WebSocket
# addresses are baked in at build time, because Vite inlines VITE_* values.

FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci
COPY . .
ARG VITE_API_URL=http://localhost:4000/api/v1
ARG VITE_WS_URL=ws://localhost:4000/ws
ENV VITE_API_URL=${VITE_API_URL} VITE_WS_URL=${VITE_WS_URL}
RUN npm run build

FROM nginx:1.30-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 8080
