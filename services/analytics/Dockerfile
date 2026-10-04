# Insight Studio's Cube (docs/deployment.md): the pinned Cube release with the data model and security baked in,
# instead of a bind mount. Build from services/analytics:  docker build -t insight-cube:<git sha> services/analytics
FROM cubejs/cube:v1.7.46@sha256:dada93b932b584680174c8460382f839dcb5cf7dbc4fcf7801799edd94a916ab
COPY cube.js security.js /cube/conf/
COPY model/ /cube/conf/model/
ENV CUBEJS_DEV_MODE=false NODE_ENV=production CUBEJS_TELEMETRY=false
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
    CMD node -e "fetch('http://127.0.0.1:4000/readyz').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"
