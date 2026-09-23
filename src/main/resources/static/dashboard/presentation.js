/* Data-to-story mapping shared by the browser dashboard and Node tests. */
(function (root) {
  const numeric = (value) => typeof value === 'number' && Number.isFinite(value);
  const formatter = new Intl.NumberFormat('vi-VN', { maximumFractionDigits: 0 });

  function money(value, currency = 'VND') {
    return numeric(value) ? `${formatter.format(value)} ${currency === 'VND' ? 'đ' : currency}` : 'Chưa có dữ liệu';
  }

  function signed(value, currency) {
    return numeric(value) ? `${value > 0 ? '+' : value < 0 ? '−' : ''}${money(Math.abs(value), currency)}` : 'Chưa có dữ liệu';
  }

  function balance(label, before, after, currency, postedAfter) {
    if (!numeric(before) || !numeric(after)) return null;
    const row = { label, before: money(before, currency), after: money(after, currency), delta: signed(after - before, currency) };
    if (numeric(postedAfter)) row.postedAfter = money(postedAfter, currency);
    return row;
  }

  function metric(label, value, currency) {
    return numeric(value) ? { label, value: money(value, currency) } : null;
  }

  /**
   * `context.first` is the first transfer's response. When it is given for a replay, the
   * result is phrased as what the viewer needs to understand — nothing was deducted again —
   * instead of repeating the original "700.000 đ đã chuyển", which reads as a second transfer.
   */
  function describeAction(action, body = {}, context = {}) {
    const currency = body.currency || 'VND';
    const result = {
      title: '', headline: '', explanation: '', status: body.status || '', tone: 'success',
      balances: [], metrics: [], journalId: body.journalId || null,
      referenceId: null, referenceLabel: '', dataNote: ''
    };
    let rows = [];
    let metrics = [];
    switch (action) {
      case 'transfer-internal':
      case 'transfer-replay': {
        const replayCheck = action === 'transfer-replay' && context.first
          ? verifyTransfer(context.first, body).replay.status : null;
        const suffix = replayCheck === 'pass' ? ' · giao dịch gốc' : '';
        if (replayCheck === 'pass') {
          result.title = 'Gửi lại cùng idempotency key';
          result.headline = 'Không trừ tiền lần hai';
          result.explanation = 'Backend nhận ra key đã xử lý và trả lại nguyên kết quả của lần chuyển đầu, cùng mã bút toán. Không có bút toán mới; số dư bên dưới là của giao dịch gốc.';
        } else if (replayCheck === 'fail') {
          result.title = 'Kết quả gửi lại';
          result.headline = 'Response gửi lại khác lần đầu';
          result.explanation = 'Mã bút toán hoặc số dư không trùng với lần chuyển đầu. Xem chi tiết kỹ thuật để điều tra.';
          result.tone = 'warning';
        } else {
          result.title = action === 'transfer-replay' ? 'Kết quả gửi lại' : 'Chuyển tiền nội bộ';
          result.headline = numeric(body.amountMinor) ? `${money(body.amountMinor, currency)} đã chuyển` : 'Giao dịch đã phản hồi';
          result.explanation = 'Số dư khả dụng lấy trực tiếp từ phản hồi chuyển tiền.';
        }
        rows = [
          balance('Tài khoản nguồn' + suffix, body.sourceAvailableBalanceBeforeMinor, body.sourceAvailableBalanceAfterMinor, currency, body.sourcePostedBalanceMinor),
          balance('Tài khoản đích' + suffix, body.destinationAvailableBalanceBeforeMinor, body.destinationAvailableBalanceAfterMinor, currency, body.destinationPostedBalanceMinor)
        ];
        result.referenceId = body.journalId || null;
        result.referenceLabel = 'Mã bút toán';
        break;
      }
      case 'payment-authorize':
        result.title = 'Giữ tiền thanh toán';
        result.headline = numeric(body.holdAmountMinor) ? `${money(body.holdAmountMinor, currency)} đã được giữ` : 'Yêu cầu giữ tiền đã phản hồi';
        result.explanation = 'Backend trả số dư ghi sổ hiện tại và số dư khả dụng trước/sau. Response không có số dư ghi sổ trước.';
        rows = [balance('Khả dụng tài khoản trả', body.availableBalanceBeforeMinor, body.availableBalanceAfterMinor, currency, body.postedBalanceMinor)];
        result.referenceId = body.holdId || null;
        result.referenceLabel = 'Mã giữ tiền';
        break;
      case 'payment-capture':
        result.title = 'Hoàn tất thanh toán';
        result.headline = numeric(body.capturedAmountMinor) ? `${money(body.capturedAmountMinor, currency)} đã ghi nhận` : 'Lệnh hoàn tất đã phản hồi';
        result.explanation = 'Response không chứa số dư trước/sau. Chỉ hiển thị số tiền và trạng thái backend trả về.';
        metrics = [metric('Còn giữ', body.remainingAmountMinor, currency)];
        result.status = [body.holdStatus, body.paymentStatus].filter(Boolean).join(' · ');
        result.referenceId = body.journalId || body.holdId || null;
        result.referenceLabel = body.journalId ? 'Mã bút toán' : 'Mã giữ tiền';
        break;
      case 'payment-void':
        result.title = 'Huỷ giữ tiền';
        result.headline = numeric(body.restoredAmountMinor) ? `${money(body.restoredAmountMinor, currency)} đã trả lại hạn mức khả dụng` : 'Yêu cầu huỷ đã phản hồi';
        result.explanation = 'Số tiền hoàn và số dư trước/sau đều do backend trả về.';
        rows = [balance('Khả dụng tài khoản trả', body.availableBalanceBeforeMinor, body.availableBalanceAfterMinor, currency)];
        result.referenceId = body.holdId || null;
        result.referenceLabel = 'Mã giữ tiền';
        break;
      case 'deposit-open':
        result.title = 'Mở tiền gửi có kỳ hạn';
        result.headline = numeric(body.principalAmountMinor) ? `${money(body.principalAmountMinor, currency)} tiền gốc` : 'Hợp đồng đã phản hồi';
        result.explanation = body.startDate && body.maturityDate ? `Từ ${body.startDate} đến ${body.maturityDate}.` : '';
        result.referenceId = body.contractId || null;
        result.referenceLabel = 'Mã hợp đồng';
        break;
      case 'deposit-accrue':
        result.title = 'Ghi nhận lãi dự thu';
        result.headline = numeric(body.accruedInterest) ? `${money(body.accruedInterest, currency)} lãi dự thu` : 'Lệnh tính lãi đã phản hồi';
        metrics = [metric('Số dư hợp đồng', body.runningBalance, currency)];
        result.explanation = body.accrualDate ? `Ngày tính lãi: ${body.accrualDate}.` : '';
        result.referenceId = body.contractId || null;
        result.referenceLabel = 'Mã hợp đồng';
        break;
      case 'deposit-maturity':
        result.title = 'Đáo hạn tiền gửi';
        result.headline = numeric(body.principalAmountMinor) && numeric(body.totalAccruedInterest)
          ? `${money(body.principalAmountMinor + body.totalAccruedInterest, currency)} gốc và lãi` : 'Lệnh đáo hạn đã phản hồi';
        metrics = [metric('Tiền gốc', body.principalAmountMinor, currency), metric('Lãi dự thu', body.totalAccruedInterest, currency)];
        result.referenceId = body.contractId || null;
        result.referenceLabel = 'Mã hợp đồng';
        break;
      case 'lending-disburse':
        result.title = 'Giải ngân khoản vay';
        result.headline = numeric(body.principalAmountMinor) ? `${money(body.principalAmountMinor, currency)} đã giải ngân` : 'Lệnh giải ngân đã phản hồi';
        result.explanation = body.firstInstallmentDueDate ? `Kỳ đầu đến hạn ${body.firstInstallmentDueDate}.` : '';
        result.referenceId = body.contractId || null;
        result.referenceLabel = 'Mã hợp đồng';
        break;
      case 'lending-repay':
        result.title = 'Trả nợ khoản vay';
        result.headline = numeric(body.amountMinor) ? `${money(body.amountMinor, currency)} đã trả` : 'Lệnh trả nợ đã phản hồi';
        metrics = [metric('Gốc', body.principalPaidMinor, currency), metric('Lãi', body.interestPaidMinor, currency),
          metric('Phí', body.feesPaidMinor, currency), metric('Gốc còn lại', body.outstandingPrincipalAfterMinor, currency)];
        result.referenceId = body.journalId || body.contractId || null;
        result.referenceLabel = body.journalId ? 'Mã bút toán' : 'Mã hợp đồng';
        break;
      default:
        result.title = 'Phản hồi từ backend';
        result.headline = body.message || 'Yêu cầu đã phản hồi';
    }
    result.balances = rows.filter(Boolean);
    result.metrics = metrics.filter(Boolean);
    if (rows.length && !result.balances.length) result.dataNote = 'Response không có đủ số dư trước/sau để đối chiếu.';
    return result;
  }

  const comparisonFields = ['journalId', 'amountMinor', 'currency',
    'sourceAvailableBalanceBeforeMinor', 'sourceAvailableBalanceAfterMinor',
    'destinationAvailableBalanceBeforeMinor', 'destinationAvailableBalanceAfterMinor'];
  const optionalComparisonFields = ['sourceAccountId', 'destinationAccountId',
    'sourcePostedBalanceMinor', 'destinationPostedBalanceMinor', 'status'];

  function verifyTransfer(first, replay) {
    let balanceEvidence = { status: 'pending', detail: 'Chạy chuyển tiền để nhận số dư thực từ backend.' };
    if (first && comparisonFields.slice(1).every((field) => first[field] !== undefined) &&
      comparisonFields.slice(1).filter((field) => field !== 'currency').every((field) => numeric(first[field]))) {
      const sourceChange = first.sourceAvailableBalanceAfterMinor - first.sourceAvailableBalanceBeforeMinor;
      const destinationChange = first.destinationAvailableBalanceAfterMinor - first.destinationAvailableBalanceBeforeMinor;
      const before = first.sourceAvailableBalanceBeforeMinor + first.destinationAvailableBalanceBeforeMinor;
      const after = first.sourceAvailableBalanceAfterMinor + first.destinationAvailableBalanceAfterMinor;
      balanceEvidence = {
        status: sourceChange === -first.amountMinor && destinationChange === first.amountMinor && before === after ? 'pass' : 'fail',
        before: money(before, first.currency), after: money(after, first.currency),
        difference: signed(after - before, first.currency),
        detail: 'Đối chiếu hai số dư khả dụng trong response; không đại diện toàn hệ thống.'
      };
    }
    let replayEvidence = { status: 'pending', detail: 'Gửi lại cùng idempotency key để so sánh response.' };
    if (first && replay) {
      const same = comparisonFields.every((field) => first[field] !== undefined && replay[field] !== undefined && first[field] === replay[field]) &&
        optionalComparisonFields.every((field) => first[field] === replay[field]);
      replayEvidence = { status: same ? 'pass' : 'fail', detail: same
        ? 'Hai response cùng mã bút toán, số tiền và số dư trước/sau.'
        : 'Response gửi lại khác hoặc thiếu dữ liệu; cần kiểm tra.' };
    }
    return { balance: balanceEvidence, replay: replayEvidence };
  }

  const shortHash = (hex) => (typeof hex === 'string' && hex.length > 12 ? `${hex.slice(0, 8)}…` : hex || '—');

  /**
   * Maps GET /api/reporting/journals/{id} onto the double-entry table. The totals and the
   * verdict come from the endpoint, which derives them from the postings themselves; this
   * only formats them, so an unbalanced journal is shown as unbalanced.
   */
  function describeJournal(journal = {}) {
    const currency = journal.currency || 'VND';
    const lines = (Array.isArray(journal.postings) ? journal.postings : []).map((posting) => {
      const debit = posting.entrySide === 'D';
      return {
        side: debit ? 'Nợ' : 'Có',
        account: posting.customerAccountNumber || posting.ledgerAccountCode || '—',
        name: [posting.ledgerAccountCode, posting.ledgerAccountName].filter(Boolean).join(' · '),
        debit: debit ? money(posting.amountMinor, posting.currency || currency) : '—',
        credit: debit ? '—' : money(posting.amountMinor, posting.currency || currency)
      };
    });
    const balanced = journal.balanced === true;
    return {
      journalId: journal.journalId || null,
      type: journal.journalType || '—',
      actor: journal.createdByActor || '—',
      createdAt: journal.createdAt || null,
      lines,
      totalDebit: money(journal.totalDebitMinor, currency),
      totalCredit: money(journal.totalCreditMinor, currency),
      difference: money(journal.differenceMinor, currency),
      balanced,
      verdict: balanced ? 'Cân đối: tổng Nợ bằng tổng Có' : 'Không cân đối: tổng Nợ khác tổng Có',
      rowHash: shortHash(journal.rowHashHex),
      prevRowHash: shortHash(journal.prevRowHashHex)
    };
  }

  const api = { money, describeAction, describeJournal, verifyTransfer };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  root.DemoPresentation = api;
})(typeof globalThis !== 'undefined' ? globalThis : this);
