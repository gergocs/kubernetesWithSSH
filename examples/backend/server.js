const express = require('express');
const app = express();
const port = 3000;

app.use(express.json());

app.get('/', (req, res) => {
  res.json({ message: 'Hello from backend service!', status: 'running' });
});

app.get('/health', (req, res) => {
  res.json({ status: 'healthy', timestamp: new Date().toISOString() });
});

app.get('/api/info', (req, res) => {
  res.json({
    name: 'backend-service',
    version: '1.0.0',
    nodeVersion: process.version,
    platform: process.platform
  });
});

app.listen(port, '0.0.0.0', () => {
  console.log(`Backend service listening on port ${port}`);
});
