(() => {
  window.__finledgerLoaded = true;
  try {
  const ZERO_UUID = "00000000-0000-0000-0000-000000000000";
  const view = window.DemoPresentation;
  const state = {
    authHeader: null, actor: "demo_admin", setup: null,
    holdId: ZERO_UUID, heldAmountMinor: null,
    depositContractId: ZERO_UUID, loanContractId: ZERO_UUID,
    firstTransferJournalId: null, firstTransferResponse: null,
    replayTransferResponse: null, lastTransferPayload: null,
    lastAction: null, lastResponse: null, resultPanel: null, demoProgress: {}
  };

  const $ = (id) => document.getElementById(id);
  const authUsername = $("auth-username");
  const authPassword = $("auth-password");
  const authState = $("auth-state");
  const setupOutput = $("setup-output");
  const responseMeta = $("response-meta");
  const responseOutput = $("response-output");
  const resultSummary = $("result-summary");
  const resultSummaryTitle = $("result-summary-title");
  const resultSummaryBody = $("result-summary-body");
  const resultSummaryDetail = $("result-summary-detail");
  const resultStatusBadge = $("result-status-badge");
  const resultJournalRow = $("result-journal-row");
  const resultReferenceLabel = $("result-reference-label");
  const resultReferenceValue = $("result-reference-value");
  const resultBalances = $("result-balances");
  const resultMetrics = $("result-metrics");
  const transferProofBlock = $("transfer-proof-block");
  const transferFirstJournalId = $("transfer-first-journal-id");
  const transferReplayJournalId = $("transfer-replay-journal-id");
  const transferProofConclusion = $("transfer-proof-conclusion");
  const rawJsonSection = $("raw-json-section");

  const actionConfig = {
    "payment-authorize": { endpoint: "/api/payments/authorize-hold", textareaId: "payment-authorize-payload" },
    "payment-capture": { endpoint: "/api/payments/capture-hold", textareaId: "payment-capture-payload" },
    "payment-void": { endpoint: "/api/payments/void-hold", textareaId: "payment-void-payload" },
    "transfer-internal": { endpoint: "/api/transfers/internal", textareaId: "transfer-payload" },
    "transfer-replay": { endpoint: "/api/transfers/internal", textareaId: "transfer-payload" },
    "deposit-open": { endpoint: "/api/deposits/open", textareaId: "deposit-open-payload" },
    "deposit-accrue": { endpoint: "/api/deposits/accrue", textareaId: "deposit-accrue-payload" },
    "deposit-maturity": { endpoint: "/api/deposits/maturity", textareaId: "deposit-maturity-payload" },
    "lending-disburse": { endpoint: "/api/lending/disburse", textareaId: "lending-disburse-payload" },
    "lending-repay": { endpoint: "/api/lending/repay", textareaId: "lending-repay-payload" }
  };

  document.querySelectorAll(".tab-btn").forEach((button) =>
    button.addEventListener("click", () => activateTab(button.dataset.tab)));
  document.querySelectorAll("[data-go]").forEach((button) =>
    button.addEventListener("click", () => activateTab(button.dataset.go)));
  $("start-demo").addEventListener("click", () => activateTab("transfer"));
  document.querySelectorAll("[data-fill]").forEach((button) => button.addEventListener("click", () => {
    authUsername.value = button.dataset.fill;
    authPassword.value = button.dataset.fill;
    document.querySelectorAll("[data-fill]").forEach((item) => item.classList.toggle("selected", item === button));
    saveCredentials();
  }));
  $("save-auth").addEventListener("click", saveCredentials);
  $("run-setup").addEventListener("click", runSetup);
  document.querySelectorAll(".action-btn").forEach((button) =>
    button.addEventListener("click", () => runAction(button.dataset.action, button)));
  $("new-transfer-key").addEventListener("click", () => {
    $("transfer-key").value = idem("transfer-internal");
    state.firstTransferResponse = null;
    state.replayTransferResponse = null;
    state.lastTransferPayload = null;
    state.firstTransferJournalId = null;
    markProgress("transfer", false);
    markProgress("replay", false);
    markProgress("verify", false);
    transferProofBlock.classList.add("hidden");
    renderVerification();
  });
  $("open-journal").addEventListener("click", openJournal);
  $("journal-close").addEventListener("click", () => $("journal-dialog").close());
  $("journal-dialog").addEventListener("click", (event) => {
    if (event.target === $("journal-dialog")) $("journal-dialog").close();
  });

  function initOnReady() {
    authUsername.value = "demo_admin";
    authPassword.value = "demo_admin";
    saveCredentials();
    refreshPayloadTemplates();
    const transferPayload = JSON.parse($("transfer-payload").value);
    $("transfer-key").value = transferPayload.idempotencyKey;
    $("transfer-amount").value = transferPayload.amountMinor;
    $("payment-amount").value = JSON.parse($("payment-authorize-payload").value).amountMinor;
    activateTab("overview");
    renderVerification();
  }
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", initOnReady);
  else initOnReady();

  function activateTab(tabName) {
    document.querySelectorAll(".tab-btn").forEach((tab) =>
      tab.classList.toggle("tab-active", tab.dataset.tab === tabName));
    document.querySelectorAll(".tab-body").forEach((panel) =>
      panel.classList.toggle("hidden", panel.dataset.panel !== tabName));
    resultSummary.classList.toggle("hidden", state.resultPanel !== tabName);
    if (tabName === "verify") renderVerification();
  }

  function saveCredentials() {
    const username = (authUsername.value || "").trim();
    const password = authPassword.value || "";
    if (!username || !password) {
      setAuthState("Chưa có credentials.", true);
      state.authHeader = null;
      return;
    }
    state.actor = username;
    state.authHeader = `Basic ${btoa(`${username}:${password}`)}`;
    setAuthState(`Dùng ${username} · được xác thực khi gọi API`);
    refreshPayloadTemplates();
  }

  async function runSetup() {
    const button = $("run-setup");
    const original = button.textContent;
    button.disabled = true;
    button.textContent = "Đang khởi tạo…";
    const result = await callApi("POST", "/api/demo/setup", null);
    renderResult("POST /api/demo/setup", result);
    button.disabled = false;
    button.textContent = original;
    if (!result.ok || !result.body || typeof result.body !== "object") {
      showRequestError(result);
      return;
    }
    state.setup = result.body;
    state.holdId = ZERO_UUID;
    state.heldAmountMinor = null;
    state.depositContractId = result.body.sampleContractIds?.maturityReadyContractId || ZERO_UUID;
    state.loanContractId = ZERO_UUID;
    state.firstTransferResponse = null;
    state.replayTransferResponse = null;
    state.lastTransferPayload = null;
    state.firstTransferJournalId = null;
    transferProofBlock.classList.add("hidden");
    $("transfer-source-preview").textContent = "Chưa có số dư từ response";
    $("transfer-destination-preview").textContent = "Chưa có số dư từ response";
    setupOutput.textContent = pretty(result.body);
    refreshPayloadTemplates();
    const transferPayload = JSON.parse($("transfer-payload").value);
    $("transfer-key").value = transferPayload.idempotencyKey;
    $("transfer-amount").value = transferPayload.amountMinor;
    $("payment-amount").value = JSON.parse($("payment-authorize-payload").value).amountMinor;
    $("setup-description").textContent = `Đã khởi tạo ${Object.keys(result.body.accountIds || {}).length} tài khoản mẫu. Chạy giao dịch để xem số dư thật từ response.`;
    markProgress("setup", true);
    ["transfer", "replay", "verify"].forEach((step) => markProgress(step, false));
    renderStory({ title: "Dữ liệu demo", headline: "Đã khởi tạo dữ liệu mẫu", explanation: result.body.note || "Payload đã lấy ID do backend trả về.", balances: [], metrics: [], referenceId: null }, "success");
    renderVerification();
  }

  async function runAction(actionName, button) {
    const config = actionConfig[actionName];
    if (!config) return;
    if (!state.setup) {
      showLocalError("Hãy khởi tạo dữ liệu demo trước khi gửi giao dịch.");
      return;
    }
    let payload;
    try {
      payload = JSON.parse($(config.textareaId).value);
    } catch (_error) {
      showLocalError("Payload JSON không hợp lệ.");
      return;
    }
    if (actionName === "transfer-internal") {
      const amount = Number($("transfer-amount").value);
      const key = $("transfer-key").value.trim();
      if (!Number.isSafeInteger(amount) || amount <= 0 || !key) {
        showLocalError("Nhập số tiền nguyên dương và idempotency key.");
        return;
      }
      payload.amountMinor = amount;
      payload.idempotencyKey = key;
      $("transfer-payload").value = pretty(payload);
    }
    if (actionName === "transfer-replay") {
      if (!state.lastTransferPayload) {
        showLocalError("Hãy chuyển tiền một lần trước khi gửi lại cùng key.");
        return;
      }
      payload = { ...state.lastTransferPayload };
    }
    if (actionName === "payment-authorize") {
      const amount = Number($("payment-amount").value);
      if (!Number.isSafeInteger(amount) || amount <= 0) {
        showLocalError("Nhập số tiền giữ nguyên dương.");
        return;
      }
      payload.amountMinor = amount;
      $(config.textareaId).value = pretty(payload);
    }
    if ((actionName === "payment-capture" || actionName === "payment-void") && state.holdId === ZERO_UUID) {
      showLocalError("Hãy giữ tiền trước khi hoàn tất hoặc huỷ.");
      return;
    }
    const original = button.textContent;
    button.disabled = true;
    button.textContent = "Đang xử lý…";
    const result = await callApi("POST", config.endpoint, payload);
    renderResult(`POST ${config.endpoint}`, result);
    button.disabled = false;
    button.textContent = original;
    if (!result.ok || !result.body || typeof result.body !== "object") {
      showRequestError(result);
      return;
    }

    const body = result.body;
    if (actionName === "payment-authorize") {
      state.holdId = body.holdId || ZERO_UUID;
      state.heldAmountMinor = body.holdAmountMinor;
      refreshPayloadTemplates();
    }
    if (actionName === "transfer-internal") {
      state.firstTransferResponse = body;
      state.replayTransferResponse = null;
      state.lastTransferPayload = { ...payload };
      state.firstTransferJournalId = body.journalId || null;
      markProgress("transfer", true);
      markProgress("replay", false);
      markProgress("verify", false);
      $("transfer-source-preview").textContent = typeof body.sourceAvailableBalanceAfterMinor === "number"
        ? `Khả dụng sau: ${view.money(body.sourceAvailableBalanceAfterMinor, body.currency)}` : "Response thiếu số dư nguồn";
      $("transfer-destination-preview").textContent = typeof body.destinationAvailableBalanceAfterMinor === "number"
        ? `Khả dụng sau: ${view.money(body.destinationAvailableBalanceAfterMinor, body.currency)}` : "Response thiếu số dư đích";
    }
    if (actionName === "transfer-replay") {
      state.replayTransferResponse = body;
      markProgress("replay", true);
    }
    if (actionName === "deposit-open" && body.contractId) {
      state.depositContractId = body.contractId;
      refreshPayloadTemplates();
    }
    if (actionName === "lending-disburse" && body.contractId) {
      state.loanContractId = body.contractId;
      refreshPayloadTemplates();
    }
    state.lastAction = actionName;
    state.lastResponse = body;
    renderStory(view.describeAction(actionName, body), "success");
    renderTransferProof();
    renderVerification();
  }

  function showLocalError(message) {
    renderLocalError(message);
    renderStory({ title: "Chưa gửi giao dịch", headline: message, explanation: "Kiểm tra dữ liệu đầu vào rồi thử lại.", balances: [], metrics: [], referenceId: null }, "error");
  }

  function showRequestError(result) {
    const message = typeof result.body === "object" && result.body?.message ? result.body.message : "Request thất bại.";
    renderStory({ title: `HTTP ${result.status} ${result.statusText}`, headline: message,
      explanation: result.status === 0 ? "Không kết nối được tới backend." : "Xem JSON kỹ thuật để biết thêm chi tiết.",
      balances: [], metrics: [], referenceId: null }, "error");
  }

  function node(tag, className, text) {
    const item = document.createElement(tag);
    if (className) item.className = className;
    if (text !== undefined) item.textContent = text;
    return item;
  }

  function renderStory(story, tone) {
    state.resultPanel = document.querySelector(".tab-btn.tab-active")?.dataset.tab || "overview";
    resultSummary.classList.remove("hidden", "error", "warning");
    if (tone !== "success") resultSummary.classList.add(tone);
    resultSummaryTitle.textContent = story.title;
    resultSummaryBody.textContent = story.headline;
    resultSummaryDetail.textContent = [story.explanation, story.status ? `Trạng thái: ${story.status}` : "", story.dataNote].filter(Boolean).join(" ");
    resultStatusBadge.textContent = tone === "success" ? "API THÀNH CÔNG" : tone === "warning" ? "CẦN KIỂM TRA" : "LỖI";
    resultBalances.replaceChildren();
    (story.balances || []).forEach((row) => {
      const card = node("div", "balance-card");
      card.append(node("h3", "", row.label));
      addBalanceLine(card, "Trước", row.before);
      addBalanceLine(card, "Sau", row.after);
      addBalanceLine(card, "Thay đổi", row.delta, row.delta.startsWith("+") ? "positive" : row.delta.startsWith("−") ? "negative" : "");
      if (row.postedAfter) addBalanceLine(card, "Ghi sổ sau", row.postedAfter);
      resultBalances.append(card);
    });
    resultMetrics.replaceChildren();
    (story.metrics || []).forEach((item) => {
      const block = node("div", "metric");
      block.append(node("span", "", item.label), node("strong", "", item.value));
      resultMetrics.append(block);
    });
    resultJournalRow.classList.toggle("hidden", !story.referenceId);
    resultReferenceLabel.textContent = story.referenceLabel || "Mã tham chiếu";
    resultReferenceValue.textContent = story.referenceId || "—";
    $("open-journal").classList.toggle("hidden", !story.journalId);
    resultSummary.scrollIntoView({ behavior: "smooth", block: "nearest" });
  }

  function addBalanceLine(card, label, value, valueClass = "") {
    const line = node("div", "balance-line");
    line.append(node("span", "", label), node("strong", valueClass, value));
    card.append(line);
  }

  function renderTransferProof() {
    if (!state.firstTransferResponse) return;
    const evidence = view.verifyTransfer(state.firstTransferResponse, state.replayTransferResponse);
    transferProofBlock.classList.remove("hidden", "warning");
    transferFirstJournalId.textContent = state.firstTransferResponse.journalId || "—";
    transferReplayJournalId.textContent = state.replayTransferResponse?.journalId || "Chưa gửi lại";
    if (evidence.replay.status === "fail" || evidence.balance.status === "fail") {
      transferProofBlock.classList.add("warning");
      transferProofConclusion.textContent = "Dữ liệu không khớp; xem response kỹ thuật để điều tra.";
    } else if (evidence.replay.status === "pass") {
      transferProofConclusion.textContent = "Cùng mã bút toán, số tiền và số dư trước/sau trong hai response.";
    } else {
      transferProofConclusion.textContent = "Đã có response lần đầu. Gửi lại cùng key để đối chiếu.";
    }
  }

  function renderVerification() {
    const evidence = view.verifyTransfer(state.firstTransferResponse, state.replayTransferResponse);
    const container = $("verify-evidence");
    container.replaceChildren();
    const balance = node("div", `verify-card ${evidence.balance.status === "pass" ? "" : evidence.balance.status}`);
    balance.append(node("h3", "", "Số dư khả dụng của hai tài khoản"));
    if (evidence.balance.before !== undefined) {
      balance.append(node("p", "", `Trước: ${evidence.balance.before}`), node("strong", "", `Sau: ${evidence.balance.after}`),
        node("p", "", `Chênh lệch: ${evidence.balance.difference}`));
    }
    balance.append(node("p", "", evidence.balance.detail));
    const replay = node("div", `verify-card ${evidence.replay.status === "pass" ? "" : evidence.replay.status}`);
    replay.append(node("h3", "", "Gửi lại cùng idempotency key"), node("strong", "", evidence.replay.status === "pass" ? "2 response · 1 journal ID" :
      evidence.replay.status === "fail" ? "Response không khớp" : "Chờ gửi lại"), node("p", "", evidence.replay.detail));
    container.append(balance, replay);
    markProgress("verify", evidence.balance.status === "pass" && evidence.replay.status === "pass");
  }

  function openJournal() {
    if (!state.lastResponse?.journalId) return;
    const story = view.describeAction(state.lastAction, state.lastResponse);
    const container = $("journal-details");
    container.replaceChildren();
    const content = node("div", "dialog-content");
    content.append(node("p", "eyebrow accent", "DỮ LIỆU TRẢ VỀ TỪ API"), node("h3", "", story.headline));
    const ref = node("div", "reference-row");
    ref.append(node("span", "", "Mã bút toán: "), node("code", "", story.journalId));
    content.append(ref);
    const balances = node("div", "balance-grid");
    story.balances.forEach((row) => {
      const card = node("div", "balance-card");
      card.append(node("h3", "", row.label));
      addBalanceLine(card, "Trước", row.before);
      addBalanceLine(card, "Sau", row.after);
      addBalanceLine(card, "Thay đổi", row.delta);
      balances.append(card);
    });
    content.append(balances);
    const payload = state.lastTransferPayload;
    if (state.lastAction.startsWith("transfer") && payload) {
      const key = node("div", "reference-row");
      key.append(node("span", "", "Idempotency key: "), node("code", "", payload.idempotencyKey));
      content.append(key);
    }
    content.append(node("p", "dialog-note", "Đây là chi tiết từ response của giao dịch. API hiện không trả journal postings, hash hay timeline thực thi, nên màn này không suy diễn các dữ liệu đó."));
    container.append(content);
    $("journal-dialog").showModal();
  }

  function markProgress(step, done) {
    state.demoProgress[step] = done;
    document.querySelectorAll(`[data-step="${step}"]`).forEach((item) => item.classList.toggle("done", Boolean(done)));
    const steps = ["setup", "transfer", "replay", "verify"];
    $("tour-progress").textContent = `${steps.filter((item) => state.demoProgress[item]).length}/4`;
  }

  async function callApi(method, path, payload) {
    const headers = { Accept: "application/json" };
    if (state.authHeader) {
      headers.Authorization = state.authHeader;
    }
    if (payload !== null) {
      headers["Content-Type"] = "application/json";
    }

    try {
      const response = await fetch(path, {
        method,
        headers,
        body: payload === null ? undefined : JSON.stringify(payload)
      });

      const text = await response.text();
      let parsedBody;
      try {
        parsedBody = text ? JSON.parse(text) : null;
      } catch (_error) {
        parsedBody = text;
      }

      return {
        ok: response.ok,
        status: response.status,
        statusText: response.statusText,
        headers: {
          limit: response.headers.get("X-RateLimit-Limit"),
          remaining: response.headers.get("X-RateLimit-Remaining"),
          retryAfter: response.headers.get("Retry-After")
        },
        body: parsedBody
      };
    } catch (error) {
      return {
        ok: false,
        status: 0,
        statusText: "NETWORK_ERROR",
        headers: {},
        body: {
          message: error instanceof Error ? error.message : "Unknown network error"
        }
      };
    }
  }

  function refreshPayloadTemplates() {
    const accountIds = state.setup?.accountIds || {};
    const productIds = state.setup?.productIds || {};
    const productVersionIds = state.setup?.productVersionIds || {};
    const ledger = state.setup?.ledgerAccountIds || {};
    const amounts = state.setup?.sampleAmountsMinor || {};

    setTextarea("payment-authorize-payload", {
      idempotencyKey: idem("pay-authorize"),
      payerAccountId: accountIds.sourceAccountId || ZERO_UUID,
      payeeAccountId: accountIds.destinationAccountId || ZERO_UUID,
      amountMinor: amounts.paymentAmountMinor ?? 500000,
      currency: "VND",
      paymentType: "CARD",
      description: "Dashboard payment hold",
      actor: state.actor,
      correlationId: uuid(),
      requestId: uuid(),
      sessionId: uuid(),
      traceId: trace("payment-auth")
    });

    setTextarea("payment-capture-payload", {
      idempotencyKey: idem("pay-capture"),
      holdId: state.holdId,
      amountMinor: state.heldAmountMinor ?? amounts.paymentAmountMinor ?? 500000,
      debitLedgerAccountId: ledger.paymentCaptureDebitLedgerAccountId || ZERO_UUID,
      creditLedgerAccountId: ledger.paymentCaptureCreditLedgerAccountId || ZERO_UUID,
      beneficiaryCustomerAccountId: accountIds.destinationAccountId || ZERO_UUID,
      actor: state.actor,
      correlationId: uuid(),
      requestId: uuid(),
      sessionId: uuid(),
      traceId: trace("payment-capture")
    });

    setTextarea("payment-void-payload", {
      idempotencyKey: idem("pay-void"),
      holdId: state.holdId,
      actor: state.actor,
      correlationId: uuid(),
      requestId: uuid(),
      sessionId: uuid(),
      traceId: trace("payment-void")
    });

    setTextarea("transfer-payload", {
      idempotencyKey: idem("transfer-internal"),
      sourceAccountId: accountIds.sourceAccountId || ZERO_UUID,
      destinationAccountId: accountIds.destinationAccountId || ZERO_UUID,
      amountMinor: amounts.transferAmountMinor || 700000,
      currency: "VND",
      debitLedgerAccountId: ledger.transferDebitLedgerAccountId || ZERO_UUID,
      creditLedgerAccountId: ledger.transferCreditLedgerAccountId || ZERO_UUID,
      description: "Dashboard internal transfer",
      actor: state.actor,
      correlationId: uuid(),
      requestId: uuid(),
      sessionId: uuid(),
      traceId: trace("transfer")
    });

    setTextarea("deposit-open-payload", {
      idempotencyKey: idem("deposit-open"),
      customerAccountId: accountIds.depositAccountId || ZERO_UUID,
      productId: productIds.termDepositProductId || ZERO_UUID,
      productVersionId: productVersionIds.termDepositVersionId || ZERO_UUID,
      principalAmountMinor: amounts.depositPrincipalMinor || 2000000,
      currency: "VND",
      interestRate: 6.5,
      termMonths: 12,
      earlyClosurePenaltyRate: 1.0,
      autoRenew: false,
      debitLedgerAccountId: ledger.depositOpenDebitLedgerAccountId || ZERO_UUID,
      creditLedgerAccountId: ledger.depositOpenCreditLedgerAccountId || ZERO_UUID,
      actor: state.actor,
      correlationId: uuid(),
      requestId: uuid(),
      sessionId: uuid(),
      traceId: trace("deposit-open")
    });

    setTextarea("deposit-accrue-payload", {
      idempotencyKey: idem("deposit-accrue"),
      contractId: state.depositContractId,
      debitLedgerAccountId: ledger.depositAccrueDebitLedgerAccountId || ZERO_UUID,
      creditLedgerAccountId: ledger.depositAccrueCreditLedgerAccountId || ZERO_UUID,
      actor: state.actor,
      correlationId: uuid(),
      requestId: uuid(),
      sessionId: uuid(),
      traceId: trace("deposit-accrue")
    });

    setTextarea("deposit-maturity-payload", {
      idempotencyKey: idem("deposit-maturity"),
      contractId: state.depositContractId,
      debitLedgerAccountId: ledger.depositMaturityDebitLedgerAccountId || ZERO_UUID,
      creditLedgerAccountId: ledger.depositMaturityCreditLedgerAccountId || ZERO_UUID,
      actor: state.actor,
      correlationId: uuid(),
      requestId: uuid(),
      sessionId: uuid(),
      traceId: trace("deposit-maturity")
    });

    setTextarea("lending-disburse-payload", {
      idempotencyKey: idem("loan-disburse"),
      borrowerAccountId: accountIds.borrowerAccountId || ZERO_UUID,
      productId: productIds.loanProductId || ZERO_UUID,
      productVersionId: productVersionIds.loanVersionId || ZERO_UUID,
      principalAmountMinor: amounts.loanDisbursementMinor || 4000000,
      currency: "VND",
      annualInterestRate: 12.0,
      termMonths: 6,
      debitLedgerAccountId: ledger.lendingDisburseDebitLedgerAccountId || ZERO_UUID,
      creditLedgerAccountId: ledger.lendingDisburseCreditLedgerAccountId || ZERO_UUID,
      actor: state.actor,
      correlationId: uuid(),
      requestId: uuid(),
      sessionId: uuid(),
      traceId: trace("loan-disburse")
    });

    setTextarea("lending-repay-payload", {
      idempotencyKey: idem("loan-repay"),
      contractId: state.loanContractId,
      payerAccountId: accountIds.borrowerAccountId || ZERO_UUID,
      amountMinor: amounts.loanRepaymentMinor || 1100000,
      currency: "VND",
      debitLedgerAccountId: ledger.lendingRepayDebitLedgerAccountId || ZERO_UUID,
      creditLedgerAccountId: ledger.lendingRepayCreditLedgerAccountId || ZERO_UUID,
      actor: state.actor,
      correlationId: uuid(),
      requestId: uuid(),
      sessionId: uuid(),
      traceId: trace("loan-repay")
    });
  }

  function setTextarea(id, payload) {
    const textarea = document.getElementById(id);
    if (!textarea) return;
    textarea.value = pretty(payload);
  }

  function renderResult(label, result) {
    const metaParts = [`${label} -> ${result.status} ${result.statusText}`];
    if (result.headers.limit) metaParts.push(`limit=${result.headers.limit}`);
    if (result.headers.remaining) metaParts.push(`remaining=${result.headers.remaining}`);
    if (result.headers.retryAfter) metaParts.push(`retryAfter=${result.headers.retryAfter}s`);

    responseMeta.textContent = metaParts.join(" | ");
    responseMeta.classList.toggle("text-[#EF4444]", !result.ok);
    responseMeta.classList.toggle("text-white/60", result.ok);
    responseOutput.textContent = pretty(result.body);

    // Show the raw JSON section
    if (rawJsonSection) {
      rawJsonSection.classList.remove("hidden");
    }
  }

  function renderLocalError(message) {
    responseMeta.textContent = message;
    responseOutput.textContent = "{}";
    responseMeta.classList.add("text-[#EF4444]");
    if (rawJsonSection) {
      rawJsonSection.classList.remove("hidden");
    }
  }

  function setAuthState(message, isError = false) {
    if (authState) {
      authState.textContent = message;
      authState.classList.toggle("text-[#EF4444]", isError);
      authState.classList.toggle("text-[#10B981]", !isError);
    }
  }

  function pretty(value) {
    return JSON.stringify(value ?? {}, null, 2);
  }

  function uuid() {
    return crypto.randomUUID();
  }

  function idem(prefix) {
    return `${prefix}-${uuid()}`;
  }

  function trace(prefix) {
    return `dashboard-${prefix}-${Date.now()}`;
  }

  } catch (e) {
    window.__finledgerError = e.message;
    window.__finledgerStack = e.stack;
    console.error('FinLedger Lab init error:', e);
  }
})();
