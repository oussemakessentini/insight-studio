# Insight Studio – web

React + TypeScript dashboard built with Vite. It talks to the Spring Boot API through the Vite
proxy (`/api` → `http://localhost:8080`; override with `API_PROXY_TARGET`).

```bat
npm install
npm run dev
npm run lint
npm run build
npm run preview
```

- `npm run dev` serves the app on http://localhost:5173.
- `npm run build` writes the production build to `dist/`.
- `npm run preview` serves `dist/` on http://localhost:4173 with the same `/api` proxy.

See the [root README](../../README.md) for the full setup, architecture and API reference.
