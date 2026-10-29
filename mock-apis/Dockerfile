FROM node:20-alpine
WORKDIR /app
COPY package*.json ./
RUN npm ci --omit=dev
COPY *.js ./
USER node
EXPOSE 4000
CMD ["node", "server.js"]
