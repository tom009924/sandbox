const GATEWAY_URL = process.env.GATEWAY_BASE_URL;
const IDENTITY_URL = GATEWAY_URL ?? process.env.IDENTITY_SERVICE_BASE_URL ?? 'http://127.0.0.1:8081';
const TODO_URL = GATEWAY_URL ?? process.env.TODO_SERVICE_BASE_URL ?? 'http://127.0.0.1:8080';
const ADMIN_API_KEY = process.env.ADMIN_API_KEY ?? 'admin-secret-key';

async function request(url, options = {}) {
  const res = await fetch(url, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      ...(options.headers ?? {}),
    },
  });
  const text = await res.text();
  let body = null;
  try {
    body = text ? JSON.parse(text) : null;
  } catch {
    body = text;
  }
  if (!res.ok) {
    throw new Error(`HTTP ${res.status} ${options.method ?? 'GET'} ${url}: ${text}`);
  }
  return body;
}

async function main() {
  const suffix = Date.now();
  const username = `ms_user_${suffix}`;
  const password = 'Password123!';

  // 1. Register & login (via api-gateway -> gRPC -> identity-service when GATEWAY_BASE_URL is set)
  const user = await request(`${IDENTITY_URL}/api/auth/register`, {
    method: 'POST',
    body: JSON.stringify({ username, password }),
  });
  console.log(`Registered user: ${user.id} (${user.username})`);

  const login = await request(`${IDENTITY_URL}/api/auth/login`, {
    method: 'POST',
    body: JSON.stringify({ username, password }),
  });
  const authHeader = { Authorization: `Bearer ${login.token}` };

  // 2. Update defaultPriority to HIGH
  await request(`${IDENTITY_URL}/api/user/settings`, {
    method: 'PUT',
    headers: authHeader,
    body: JSON.stringify({
      defaultPriority: 'HIGH',
      defaultEstimatedMinutes: 45,
    }),
  });

  // 3. Create a Todo without specifying priority -> todo-api fetches HIGH from identity-service
  const todo = await request(`${TODO_URL}/api/todos`, {
    method: 'POST',
    headers: {
      ...authHeader,
      'Idempotency-Key': `ms-e2e-${suffix}`,
    },
    body: JSON.stringify({
      title: 'Combined microservices todo',
    }),
  });
  if (todo.priority !== 'HIGH') {
    throw new Error(`Expected priority HIGH from identity-service settings, got ${todo.priority}`);
  }
  console.log(`Created todo with inherited priority: ${todo.priority}`);

  // 4. Create a project and list members -> resolves username via identity-service
  const project = await request(`${TODO_URL}/api/projects`, {
    method: 'POST',
    headers: authHeader,
    body: JSON.stringify({ name: `Project ${suffix}` }),
  });
  const members = await request(`${TODO_URL}/api/projects/${project.id}/members`, {
    headers: authHeader,
  });
  if (!members.some((m) => m.username === username)) {
    throw new Error(`Expected member ${username} resolved from identity-service`);
  }
  console.log(`Verified project member resolution`);

  // 5. Check admin users and admin stats
  const adminUsers = await request(`${IDENTITY_URL}/api/admin/users`, {
    headers: { 'X-Admin-Key': ADMIN_API_KEY },
  });
  if (!adminUsers.some((u) => u.username === username)) {
    throw new Error(`Expected ${username} in /api/admin/users`);
  }

  const stats = await request(`${TODO_URL}/api/admin/stats`, {
    headers: { 'X-Admin-Key': ADMIN_API_KEY },
  });
  if (stats.totalUsers < 1) {
    throw new Error(`Expected totalUsers >= 1, got ${stats.totalUsers}`);
  }
  console.log(`Combined microservices E2E test passed! (totalUsers=${stats.totalUsers})`);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
