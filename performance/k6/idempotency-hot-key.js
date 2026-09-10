import http from 'k6/http';
import { check, fail } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const BASE_URL = (__ENV.BASE_URL || 'http://localhost:8080').replace(/\/$/, '');
const TOKEN = __ENV.JWT_TOKEN || '';
const VUS = Number(__ENV.VUS || 20);
const DURATION = __ENV.DURATION || '30s';
const RUN_ID = __ENV.RUN_ID || `${Date.now()}`;
const IDEMPOTENCY_KEY = `hot-key-${RUN_ID}`;
const BODY = JSON.stringify({ amount: 42.50, currency: 'USD' });

const retryLatency = new Trend('hot_key_retry_latency_ms', true);
const mismatches = new Counter('hot_key_id_mismatch');

export const options = {
  scenarios: {
    hot_key: {
      executor: 'constant-vus',
      vus: VUS,
      duration: DURATION,
    },
  },
  thresholds: {
    checks: ['rate>0.999'],
    http_req_failed: ['rate<0.001'],
    hot_key_retry_latency_ms: ['p(95)<250', 'p(99)<500'],
    hot_key_id_mismatch: ['count<1'],
  },
};

function params() {
  return {
    headers: {
      Authorization: `Bearer ${TOKEN}`,
      'Content-Type': 'application/json',
      'Idempotency-Key': IDEMPOTENCY_KEY,
    },
    tags: { operation: 'idempotency-hot-key' },
  };
}

function paymentId(response) {
  try {
    return response.json('id');
  } catch (_) {
    return null;
  }
}

export function setup() {
  if (!TOKEN) {
    fail('JWT_TOKEN is required and must include payments:write');
  }

  const seeded = http.post(`${BASE_URL}/api/v1/payments`, BODY, params());
  if (seeded.status !== 201 || !paymentId(seeded)) {
    fail(`failed to seed hot-key payment: status=${seeded.status}`);
  }
  return { paymentId: paymentId(seeded) };
}

export default function (data) {
  const response = http.post(`${BASE_URL}/api/v1/payments`, BODY, params());
  retryLatency.add(response.timings.duration);

  const id = paymentId(response);
  const samePayment = response.status === 201 && id === data.paymentId;
  if (!samePayment) {
    mismatches.add(1);
  }

  check(response, {
    'hot-key retry returns 201': (result) => result.status === 201,
    'hot-key retry returns seeded payment': () => samePayment,
  });
}
