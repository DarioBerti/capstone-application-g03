# syntax=docker/dockerfile:1
# Multi-stage build for the EuroTransit debug SPA.
#  - stage 1 builds the static bundle with Vite
#  - stage 2 serves it via nginx with SPA history fallback
# VITE_* are baked at BUILD time, so the Keycloak URL is a build-arg:
#   kind:    --build-arg VITE_KEYCLOAK_URL=https://auth.localhost
#   prod CI: --build-arg VITE_KEYCLOAK_URL=https://auth.g03.cpo2026.it
# API calls are same-origin relative /api/* (routed by the ingress), so no API base is needed here.
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
ARG VITE_KEYCLOAK_URL=https://auth.g03.cpo2026.it
# Write to .env.production so `vite build` (mode=production) picks it up deterministically.
RUN echo "VITE_KEYCLOAK_URL=$VITE_KEYCLOAK_URL" > .env.production && npm run build

FROM nginx:1.27-alpine AS runtime
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
