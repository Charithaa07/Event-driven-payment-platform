import http from 'k6/http';
import { check, fail, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const BASE_URL = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/$/, '');
const TOKEN = __ENV.JWT_TOKEN || '';
const RATE = Number(__ENV.RATE || 25);
const DURATION = __ENV.DURATION || '1m';
const PRE_ALLOCATED_VUS = Number(__ENV.PRE_ALLOCATED_VUS || 12);
const MAX_VUS = Number(__ENV.MAX_VUS || 60);
const RETRY_RATIO = Math.min(1, Math.max(0, Number(__ENV.RETRY_RATIO || 0.25)));
const THINK_TIME_SECONDS = Number(__ENV.THINK_TIME_SECONDS || 0.05);

const createLatency = new Trend('payment_create_latency_ms', true);
const retryLatency = new Trend('payment_retry_latency_ms', true);
const readLatency = new Trend('payment_read_latency_ms', true);
const idempotencyMismatch = new Counter('idempotency_mismatch');

export const options = {
  scenarios: {
    payment_api: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: PRE_ALLOCATED_VUS,
      maxVUs: MAX_VUS,
    },
  },
  thresholds: {
    checks: ['rate>0.99'],
    http_req_failed: ['rate<0.01'],
    payment_create_latency_ms: ['p(95)<500', 'p(99)<1000'],
    payment_retry_latency_ms: ['p(95)<300'],
    payment_read_latency_ms: ['p(95)<300'],
    idempotency_mismatch: ['count<1'],
  },
};

export function setup() {
  if (!TOKEN) {
    fail('JWT_TOKEN is required and must include payments:write and payments:read scopes');
  }
}

function parseJson(response) {
  try {
    return response.json();
  } catch (_) {
    return null;
  }
}

function requestHeaders(idempotencyKey) {
  return {
    headers: {
      Authorization: `Bearer ${TOKEN}`,
      'Content-Type': 'application/json',
      'Idempotency-Key': idempotencyKey,
    },
  };
}

export default function () {
  const idempotencyKey = `perf-${__VU}-${__ITER}-${Date.now()}`;
  const body = JSON.stringify({ amount: 42.50, currency: 'USD' });

  const created = http.post(`${BASE_URL}/api/v1/payments`, body, {
    ...requestHeaders(idempotencyKey),
    tags: { operation: 'payment-create' },
  });
  createLatency.add(created.timings.duration);

  const createOk = check(created, {
    'create returns 201': (response) => response.status === 201,
  });
  if (!createOk) {
    sleep(THINK_TIME_SECONDS);
    return;
  }

  const createdBody = parseJson(created);
  const paymentId = createdBody && createdBody.id;
  check(paymentId, {
    'create response contains payment id': (id) => Boolean(id),
  });
  if (!paymentId) {
    sleep(THINK_TIME_SECONDS);
    return;
  }

  if (Math.random() < RETRY_RATIO) {
    const retried = http.post(`${BASE_URL}/api/v1/payments`, body, {
      ...requestHeaders(idempotencyKey),
      tags: { operation: 'payment-idempotent-retry' },
    });
    retryLatency.add(retried.timings.duration);
    const retryBody = parseJson(retried);
    const samePayment = retried.status === 201 && retryBody && retryBody.id === paymentId;
    if (!samePayment) {
      idempotencyMismatch.add(1);
    }
    check(retried, {
      'idempotent retry returns 201': (response) => response.status === 201,
      'idempotent retry returns original payment': () => samePayment,
    });
  }

  const read = http.get(`${BASE_URL}/api/v1/payments/${paymentId}`, {
    headers: { Authorization: `Bearer ${TOKEN}` },
    tags: { operation: 'payment-read' },
  });
  readLatency.add(read.timings.duration);
  check(read, {
    'read returns 200': (response) => response.status === 200,
    'read returns created payment': (response) => {
      const responseBody = parseJson(response);
      return responseBody && responseBody.id === paymentId;
    },
  });

  sleep(THINK_TIME_SECONDS);
}
