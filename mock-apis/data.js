// Seed data for the mock legacy APIs. All people, emails and addresses are fictional.
// Orders include PII fields on purpose: the orchestrator's tool adapter must strip them.

export const users = {
  'u-1001': { name: 'Asha Verma', email: 'asha.verma@example.com', phone: '+91 98765 43210' },
  'u-1002': { name: 'Daniel Brooks', email: 'daniel.brooks@example.com', phone: '+1 415-555-0187' },
};

export const orders = [
  {
    id: 'ORD-1001', userId: 'u-1001', status: 'shipped', placedAt: '2026-10-24', eta: '2026-10-31', carrier: 'BlueDart',
    items: [{ sku: 'SKU-HEADPHONES', name: 'Wireless headphones', qty: 1, price: 129.99 }],
    total: 129.99, currency: 'USD',
    shippingAddress: { name: 'Asha Verma', line1: '14 Lake View Road', city: 'Pune', pincode: '411001' },
  },
  {
    id: 'ORD-1002', userId: 'u-1001', status: 'placed', placedAt: '2026-10-27', eta: '2026-11-03', carrier: null,
    items: [
      { sku: 'SKU-KEYBOARD', name: 'Mechanical keyboard', qty: 1, price: 89.0 },
      { sku: 'SKU-MOUSEPAD', name: 'Desk mat', qty: 2, price: 15.0 },
    ],
    total: 119.0, currency: 'USD',
    shippingAddress: { name: 'Asha Verma', line1: '14 Lake View Road', city: 'Pune', pincode: '411001' },
  },
  {
    id: 'ORD-1003', userId: 'u-1001', status: 'delivered', placedAt: '2026-10-02', eta: '2026-10-06', carrier: 'Delhivery',
    items: [{ sku: 'SKU-CHARGER', name: '65W USB-C charger', qty: 1, price: 39.0 }],
    total: 39.0, currency: 'USD',
    shippingAddress: { name: 'Asha Verma', line1: '14 Lake View Road', city: 'Pune', pincode: '411001' },
  },
  {
    id: 'ORD-1004', userId: 'u-1001', status: 'placed', placedAt: '2026-10-28', eta: null, carrier: null,
    items: [{ sku: 'SKU-MONITOR', name: '27-inch 4K monitor', qty: 1, price: 349.0 }],
    total: 349.0, currency: 'USD',
    shippingAddress: { name: 'Asha Verma', line1: '14 Lake View Road', city: 'Pune', pincode: '411001' },
  },
  {
    id: 'ORD-2001', userId: 'u-1002', status: 'packed', placedAt: '2026-10-26', eta: '2026-10-30', carrier: null,
    items: [{ sku: 'SKU-BACKPACK', name: 'Travel backpack', qty: 1, price: 74.5 }],
    total: 74.5, currency: 'USD',
    shippingAddress: { name: 'Daniel Brooks', line1: '220 Market Street', city: 'San Francisco', pincode: '94105' },
  },
];

export const inventory = {
  'SKU-HEADPHONES': { sku: 'SKU-HEADPHONES', name: 'Wireless headphones', available: 42, warehouse: 'PNQ-1', restockDate: null },
  'SKU-KEYBOARD': { sku: 'SKU-KEYBOARD', name: 'Mechanical keyboard', available: 0, warehouse: 'PNQ-1', restockDate: '2026-11-10' },
  'SKU-MOUSEPAD': { sku: 'SKU-MOUSEPAD', name: 'Desk mat', available: 310, warehouse: 'PNQ-2', restockDate: null },
  'SKU-CHARGER': { sku: 'SKU-CHARGER', name: '65W USB-C charger', available: 8, warehouse: 'BLR-1', restockDate: null },
  'SKU-MONITOR': { sku: 'SKU-MONITOR', name: '27-inch 4K monitor', available: 3, warehouse: 'BLR-1', restockDate: null },
  'SKU-BACKPACK': { sku: 'SKU-BACKPACK', name: 'Travel backpack', available: 17, warehouse: 'SFO-1', restockDate: null },
};

export const payments = {
  'ORD-1001': { orderId: 'ORD-1001', status: 'captured', method: 'card', cardLast4: '4242', amount: 129.99, currency: 'USD' },
  'ORD-1002': { orderId: 'ORD-1002', status: 'captured', method: 'upi', amount: 119.0, currency: 'USD' },
  'ORD-1003': { orderId: 'ORD-1003', status: 'captured', method: 'card', cardLast4: '1881', amount: 39.0, currency: 'USD' },
  'ORD-1004': { orderId: 'ORD-1004', status: 'failed', method: 'card', cardLast4: '0005', amount: 349.0, currency: 'USD' },
  'ORD-2001': { orderId: 'ORD-2001', status: 'captured', method: 'wallet', amount: 74.5, currency: 'USD' },
};
