import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const AUTH_TOKEN = __ENV.AUTH_TOKEN || '';
const PROFILE = __ENV.PROFILE || 'baseline';

const paymentCreated = new Counter('payment_created');
const paymentConflicts = new Counter('payment_conflicts');
const paymentLatency = new Trend('payment_create_latency', true);

const profiles = {
  smoke: {
    vus: 1,
    duration: '20s',
  },
  baseline: {
    stages: [
      { duration: '30s', target: 10 },
      { duration: '2m', target: 10 },
      { duration: '30s', target: 0 },
    ],
  },
  stress: {
    stages: [
      { duration: '1m', target: 25 },
      { duration: '2m', target: 50 },
      { duration: '2m', target: 100 },
      { duration: '1m', target: 0 },
    ],
  },
};

export const options = {
  ...(profiles[PROFILE] || profiles.baseline),
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<500', 'p(99)<1000'],
    payment_create_latency: ['p(95)<500'],
  },
};

function headers(idempotencyKey) {
  const result = {
    'Content-Type': 'application/json',
    'Idempotency-Key': idempotencyKey,
  };

  if (AUTH_TOKEN) {
    result.Authorization = `Bearer ${AUTH_TOKEN}`;
  }

  return result;
}

export default function () {
  const idempotencyKey = `perf-${__VU}-${__ITER}-${Date.now()}`;
  const payload = JSON.stringify({
    amount: '42.50',
    currency: 'USD',
  });

  const response = http.post(`${BASE_URL}/api/v1/payments`, payload, {
    headers: headers(idempotencyKey),
    tags: { operation: 'create-payment' },
  });

  paymentLatency.add(response.timings.duration);

  if (response.status === 200 || response.status === 201) {
    paymentCreated.add(1);
  }
  if (response.status === 409) {
    paymentConflicts.add(1);
  }

  check(response, {
    'payment accepted': (r) => r.status === 200 || r.status === 201,
    'response has payment id': (r) => {
      try {
        return Boolean(r.json('paymentId'));
      } catch (_) {
        return false;
      }
    },
  });

  sleep(0.1);
}
