const test = require('node:test');
const assert = require('node:assert/strict');
const view = require('../../main/resources/static/dashboard/presentation.js');

test('transfer shows both available balances before and after from the response', () => {
  const result = view.describeAction('transfer-internal', {
    journalId: 'journal-1', amountMinor: 700000, currency: 'VND', status: 'COMPLETED',
    sourceAvailableBalanceBeforeMinor: 80000000,
    sourceAvailableBalanceAfterMinor: 79300000,
    sourcePostedBalanceMinor: 79300000,
    destinationAvailableBalanceBeforeMinor: 25000000,
    destinationAvailableBalanceAfterMinor: 25700000,
    destinationPostedBalanceMinor: 25700000
  });

  assert.equal(result.headline, '700.000 đ đã chuyển');
  assert.deepEqual(result.balances.map(({ label, before, after }) => [label, before, after]), [
    ['Tài khoản nguồn', '80.000.000 đ', '79.300.000 đ'],
    ['Tài khoản đích', '25.000.000 đ', '25.700.000 đ']
  ]);
  assert.equal(result.journalId, 'journal-1');
});

test('authorize and void display the actual available-balance change, including zero', () => {
  const held = view.describeAction('payment-authorize', {
    holdId: 'hold-1', holdAmountMinor: 500000, currency: 'VND', status: 'AUTHORIZED',
    postedBalanceMinor: 80000000, availableBalanceBeforeMinor: 80000000,
    availableBalanceAfterMinor: 79500000
  });
  assert.deepEqual(held.balances[0], {
    label: 'Khả dụng tài khoản trả', before: '80.000.000 đ', after: '79.500.000 đ',
    delta: '−500.000 đ', postedAfter: '80.000.000 đ'
  });

  const voided = view.describeAction('payment-void', {
    holdId: 'hold-1', restoredAmountMinor: 500000, currency: 'VND', status: 'VOIDED',
    availableBalanceBeforeMinor: 79500000, availableBalanceAfterMinor: 80000000
  });
  assert.equal(voided.headline, '500.000 đ đã trả lại hạn mức khả dụng');
  assert.equal(voided.balances[0].after, '80.000.000 đ');

  const zero = view.describeAction('payment-void', {
    restoredAmountMinor: 0, currency: 'VND', availableBalanceBeforeMinor: 0,
    availableBalanceAfterMinor: 0
  });
  assert.equal(zero.balances[0].before, '0 đ');
  assert.equal(zero.balances[0].after, '0 đ');
});

test('transfer verification uses the returned changes and checks replay content', () => {
  const first = {
    journalId: 'journal-1', amountMinor: 700000, currency: 'VND',
    sourceAvailableBalanceBeforeMinor: 80000000,
    sourceAvailableBalanceAfterMinor: 79300000,
    destinationAvailableBalanceBeforeMinor: 25000000,
    destinationAvailableBalanceAfterMinor: 25700000
  };
  const evidence = view.verifyTransfer(first, { ...first });
  assert.equal(evidence.balance.status, 'pass');
  assert.equal(evidence.balance.before, '105.000.000 đ');
  assert.equal(evidence.balance.after, '105.000.000 đ');
  assert.equal(evidence.replay.status, 'pass');

  assert.equal(view.verifyTransfer(first, { ...first, sourceAvailableBalanceAfterMinor: 78600000 }).replay.status, 'fail');
  assert.equal(view.verifyTransfer({ ...first, sourcePostedBalanceMinor: 79300000 },
    { ...first, sourcePostedBalanceMinor: 78600000 }).replay.status, 'fail');
  assert.equal(view.verifyTransfer(first, null).replay.status, 'pending');
  assert.equal(view.verifyTransfer({ journalId: 'journal-1' }, null).balance.status, 'pending');
});
