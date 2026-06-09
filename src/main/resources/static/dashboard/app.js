(() => {
  window.__finledgerLoaded = true;
  try {

  const ZERO_UUID = "00000000-0000-0000-0000-000000000000";

  const state = {
    authHeader: null,
    actor: "demo_admin",
    setup: null,
    holdId: ZERO_UUID,
    depositContractId: ZERO_UUID,
    loanContractId: ZERO_UUID,
    firstTransferJournalId: null,
    demoProgress: {}
  };

  // Hidden form elements (preserved from existing app.js for payload templates)
  const authUsername = document.getElementById("auth-username");
  const authPassword = document.getElementById("auth-password");
  const authState = document.getElementById("auth-state");
  const setupOutput = document.getElementById("setup-output");
  const responseMeta = document.getElementById("response-meta");
  const responseOutput = document.getElementById("response-output");
  const resultSummary = document.getElementById("result-summary");
  const resultSummaryTitle = document.getElementById("result-summary-title");
  const resultSummaryBody = document.getElementById("result-summary-body");
  const resultSummaryDetail = document.getElementById("result-summary-detail");

  // New UI elements
  const resultStatusBadge = document.getElementById("result-status-badge");
  const resultJournalRow = document.getElementById("result-journal-row");
  const transferProofBlock = document.getElementById("transfer-proof-block");
  const transferFirstJournalId = document.getElementById("transfer-first-journal-id");
  const transferReplayJournalId = document.getElementById("transfer-replay-journal-id");
  const transferProofConclusion = document.getElementById("transfer-proof-conclusion");
  const processingFlow = document.getElementById("processing-flow");
  const processingFlowSteps = document.getElementById("processing-flow-steps");
  const rawJsonSection = document.getElementById("raw-json-section");
  const adminBadge = document.getElementById("admin-badge");
  const refreshPanelBtn = document.getElementById("refresh-panel");

  const actionConfig = {
    "payment-authorize": { endpoint: "/api/payments/authorize-hold", textareaId: "payment-authorize-payload", flowLabel: "payment-authorize" },
    "payment-capture": { endpoint: "/api/payments/capture-hold", textareaId: "payment-capture-payload", flowLabel: "payment-capture" },
    "payment-void": { endpoint: "/api/payments/void-hold", textareaId: "payment-void-payload", flowLabel: "payment-void" },
    "transfer-internal": { endpoint: "/api/transfers/internal", textareaId: "transfer-payload", flowLabel: "transfer" },
    "transfer-replay": { endpoint: "/api/transfers/internal", textareaId: "transfer-payload", flowLabel: "transfer-replay" },
    "deposit-open": { endpoint: "/api/deposits/open", textareaId: "deposit-open-payload", flowLabel: "deposit" },
    "deposit-accrue": { endpoint: "/api/deposits/accrue", textareaId: "deposit-accrue-payload", flowLabel: "deposit" },
    "deposit-maturity": { endpoint: "/api/deposits/maturity", textareaId: "deposit-maturity-payload", flowLabel: "deposit" },
    "lending-disburse": { endpoint: "/api/lending/disburse", textareaId: "lending-disburse-payload", flowLabel: "lending" },
    "lending-repay": { endpoint: "/api/lending/repay", textareaId: "lending-repay-payload", flowLabel: "lending" }
  };

  // Tab switching
  document.querySelectorAll(".tab-btn").forEach((button) => {
    button.addEventListener("click", () => activateTab(button.dataset.tab));
  });

  // Data-fill buttons (sidebar account selector)
  document.querySelectorAll("[data-fill]").forEach((button) => {
    button.addEventListener("click", () => {
      const user = button.dataset.fill;
      authUsername.value = user;
      authPassword.value = user;
      saveCredentials();
    });
  });

  document.getElementById("save-auth").addEventListener("click", saveCredentials);
  document.getElementById("run-setup").addEventListener("click", runSetup);

  // Action buttons
  document.querySelectorAll(".action-btn").forEach((button) => {
    button.addEventListener("click", () => runAction(button.dataset.action));
  });

  // Refresh panel button
  if (refreshPanelBtn) {
    refreshPanelBtn.addEventListener("click", () => {
      resultSummary.style.display = "none";
      transferProofBlock.classList.add("hidden");
      processingFlow.classList.add("hidden");
      rawJsonSection.classList.add("hidden");
      responseMeta.textContent = "Chưa gửi request.";
      responseOutput.textContent = "{}";
    });
  }

  // Auto-fill demo_admin on load (wrapped in DOMContentLoaded for reliability)
  function initOnReady() {
    authUsername.value = "demo_admin";
    authPassword.value = "demo_admin";
    saveCredentials();
    refreshPayloadTemplates();
  }
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", initOnReady);
  } else {
    initOnReady();
  }

  function activateTab(tabName) {
    document.querySelectorAll(".tab-btn").forEach((tab) => {
      const isActive = tab.dataset.tab === tabName;
      tab.classList.toggle("tab-active", isActive);
    });
    document.querySelectorAll(".tab-body").forEach((panel) => {
      if (panel.dataset.panel === tabName) {
        panel.classList.remove("hidden");
      } else {
        panel.classList.add("hidden");
      }
    });
  }

  function saveCredentials() {
    const username = (authUsername.value || "").trim();
    const password = authPassword.value || "";
    if (!username || !password) {
      setAuthState("Chưa có credentials.", true);
      return;
    }
    state.actor = username;
    state.authHeader = `Basic ${btoa(`${username}:${password}`)}`;
    setAuthState(`Credentials: ${username}`);
    markProgress("admin", true);
    if (adminBadge) {
      adminBadge.classList.remove("hidden");
    }
    refreshPayloadTemplates();
  }

  async function runSetup() {
    const btn = document.getElementById("run-setup");
    const origText = btn.innerHTML;
    btn.innerHTML = '<span class="material-symbols-outlined animate-spin text-[20px]">sync</span> Đang khởi tạo...';
    btn.disabled = true;

    const result = await callApi("POST", "/api/demo/setup", null);
    renderResult("POST /api/demo/setup", result);

    btn.innerHTML = origText;
    btn.disabled = false;

    if (result.ok && result.body) {
      state.setup = result.body;
      if (state.setup.sampleContractIds && state.setup.sampleContractIds.maturityReadyContractId) {
        state.depositContractId = state.setup.sampleContractIds.maturityReadyContractId;
      }
      setupOutput.textContent = pretty(result.body);
      markProgress("setup", true);
      showResultSummary(
        "SUCCESS",
        "Demo Data Initialized",
        "Customer, account, product, and ledger IDs loaded into all payload templates.",
        null,
        null
      );
      showProcessingFlow("setup");
      refreshPayloadTemplates();
    }
  }

  async function runAction(actionName) {
    const config = actionConfig[actionName];
    if (!config) return;

    const textarea = document.getElementById(config.textareaId);
    if (!textarea) return;

    let payload;
    try {
      payload = JSON.parse(textarea.value);
    } catch (_error) {
      renderLocalError(`Invalid JSON payload for ${actionName}.`);
      return;
    }

    // Show loading state on the clicked button
    const btn = document.querySelector(`.action-btn[data-action="${actionName}"]`);
    const origText = btn ? btn.innerHTML : null;
    if (btn) {
      btn.innerHTML = '<span class="material-symbols-outlined animate-spin">sync</span>';
      btn.disabled = true;
    }

    const result = await callApi("POST", config.endpoint, payload);
    renderResult(`POST ${config.endpoint}`, result);

    if (btn) {
      btn.innerHTML = origText;
      btn.disabled = false;
    }

    if (!result.ok || !result.body) {
      showResultSummary("ERROR", `Status: ${result.status} ${result.statusText}`, result.body?.message || "Request failed.", null, null);
      return;
    }

    // --- Payment Authorize ---
    if (actionName === "payment-authorize" && result.body.holdId) {
      state.holdId = result.body.holdId;
      markProgress("authorize", true);
      const status = result.body.status || "AUTHORIZED";
      showResultSummary(
        "SUCCESS",
        "Payment Authorized",
        `Hold ${result.body.holdId} — Status: ${status}`,
        result.body.journalId || null,
        `Amount: ${result.body.holdAmountMinor || "—"} ${result.body.currency || ""}`
      );
      showProcessingFlow("payment-authorize");
      refreshPayloadTemplates();
      return;
    }

    // --- Payment Capture ---
    if (actionName === "payment-capture") {
      markProgress("capture", true);
      const holdStatus = result.body.holdStatus || "—";
      const paymentStatus = result.body.paymentStatus || "—";
      showResultSummary(
        "SUCCESS",
        "Payment Captured",
        `Hold: ${holdStatus}, Payment: ${paymentStatus}`,
        result.body.journalId || null,
        `Captured: ${result.body.capturedAmountMinor || "—"} ${result.body.currency || ""}`
      );
      showProcessingFlow("payment-capture");
      return;
    }

    // --- Payment Void ---
    if (actionName === "payment-void") {
      const holdStatus = result.body.status || result.body.holdStatus || "VOIDED";
      showResultSummary(
        "SUCCESS",
        "Hold Voided",
        `Hold released — Status: ${holdStatus}`,
        null,
        "Available balance restored."
      );
      showProcessingFlow("payment-void");
      return;
    }

    // --- Transfer ---
    if (actionName === "transfer-internal") {
      markProgress("transfer", true);
      if (!state.firstTransferJournalId) {
        state.firstTransferJournalId = result.body.journalId;
      }
      // Show proof block with partial data (replay not yet)
      if (transferProofBlock) {
        transferProofBlock.classList.remove("hidden");
        transferFirstJournalId.textContent = state.firstTransferJournalId || "—";
        transferReplayJournalId.textContent = "Chưa replay";
        transferProofConclusion.textContent = "Chạy Replay để xác minh idempotency.";
        transferProofConclusion.className = "pt-2 border-t border-[#F59E0B]/10 text-[#F59E0B] font-bold";
      }
      showResultSummary(
        "SUCCESS",
        "Transfer Completed",
        `Journal: ${result.body.journalId || "—"}`,
        result.body.journalId || null,
        `Status: ${result.body.status || "COMPLETED"} | Amount: ${result.body.amountMinor || "—"} ${result.body.currency || ""}`
      );
      showProcessingFlow("transfer");
      return;
    }

    // --- Transfer Replay ---
    if (actionName === "transfer-replay") {
      const currentJournalId = result.body.journalId;
      if (state.firstTransferJournalId && currentJournalId === state.firstTransferJournalId) {
        markProgress("replay", true);
        markProgress("verify", true);
        if (transferProofBlock) {
          transferProofBlock.classList.remove("hidden");
          transferFirstJournalId.textContent = state.firstTransferJournalId;
          transferReplayJournalId.textContent = currentJournalId;
          transferProofConclusion.textContent = "Kết luận: Trùng journalId = không double-post";
          transferProofConclusion.className = "pt-2 border-t border-[#10B981]/10 text-[#10B981] font-bold";
        }
        showResultSummary(
          "SUCCESS",
          "Idempotency Verified",
          `Same journalId returned: <code class="font-mono bg-[#f4f3f7] px-1 rounded text-[13px]">${currentJournalId}</code> — no double-post.`,
          currentJournalId,
          "Idempotency key replay correctly returned the original transfer result."
        );
      } else if (state.firstTransferJournalId) {
        if (transferProofBlock) {
          transferProofBlock.classList.remove("hidden");
          transferFirstJournalId.textContent = state.firstTransferJournalId;
          transferReplayJournalId.textContent = currentJournalId || "—";
          transferProofConclusion.textContent = `Cảnh báo: journalId khác với lần đầu (${state.firstTransferJournalId}).`;
          transferProofConclusion.className = "pt-2 border-t border-[#F59E0B]/10 text-[#F59E0B] font-bold";
        }
        showResultSummary(
          "WARNING",
          "Replay Result",
          `Journal: ${currentJournalId || "—"}`,
          currentJournalId || null,
          `Note: journalId differs from first transfer (${state.firstTransferJournalId}).`
        );
      } else {
        showResultSummary(
          "INFO",
          "Replay Result",
          `Journal: ${currentJournalId || "—"}`,
          currentJournalId || null,
          "Run a transfer first before replay."
        );
      }
      showProcessingFlow("transfer-replay");
      return;
    }

    // --- Deposit Open ---
    if (actionName === "deposit-open" && result.body.contractId) {
      state.depositContractId = result.body.contractId;
      showResultSummary(
        "SUCCESS",
        "Deposit Opened",
        `Contract: ${result.body.contractId}`,
        null,
        ""
      );
      showProcessingFlow("deposit");
      refreshPayloadTemplates();
      return;
    }

    // --- Deposit Accrue ---
    if (actionName === "deposit-accrue") {
      showResultSummary(
        "SUCCESS",
        "Interest Accrued",
        result.body.message || `Contract: ${result.body.contractId || state.depositContractId}`,
        null,
        ""
      );
      showProcessingFlow("deposit");
      return;
    }

    // --- Deposit Maturity ---
    if (actionName === "deposit-maturity") {
      showResultSummary(
        "SUCCESS",
        "Maturity Processed",
        result.body.message || `Contract: ${result.body.contractId || state.depositContractId}`,
        null,
        ""
      );
      showProcessingFlow("deposit");
      return;
    }

    // --- Lending Disburse ---
    if (actionName === "lending-disburse" && result.body.contractId) {
      state.loanContractId = result.body.contractId;
      showResultSummary(
        "SUCCESS",
        "Loan Disbursed",
        `Contract: ${result.body.contractId}`,
        null,
        ""
      );
      showProcessingFlow("lending");
      refreshPayloadTemplates();
      return;
    }

    // --- Lending Repay ---
    if (actionName === "lending-repay") {
      showResultSummary(
        "SUCCESS",
        "Loan Repaid",
        result.body.message || `Contract: ${result.body.contractId || state.loanContractId}`,
        null,
        ""
      );
      showProcessingFlow("lending");
      return;
    }

    // Fallback for any other action
    showResultSummary(
      result.ok ? "SUCCESS" : "ERROR",
      actionName,
      pretty(result.body).substring(0, 200),
      null,
      ""
    );
  }

  function showResultSummary(status, title, body, journalId, detail) {
    resultSummary.style.display = "block";

    // Status badge
    if (resultStatusBadge) {
      resultStatusBadge.textContent = status;
      resultStatusBadge.className = "px-3 py-1 rounded-full text-[12px] tracking-wider font-bold flex items-center gap-1";
      if (status === "SUCCESS") {
        resultStatusBadge.classList.add("bg-[#10B981]/10", "text-[#10B981]");
        resultStatusBadge.innerHTML = '<span class="material-symbols-outlined text-[16px]" style="font-variation-settings:\'FILL\'1">check_circle</span> SUCCESS';
      } else if (status === "ERROR") {
        resultStatusBadge.classList.add("bg-[#EF4444]/10", "text-[#EF4444]");
        resultStatusBadge.innerHTML = '<span class="material-symbols-outlined text-[16px]" style="font-variation-settings:\'FILL\'1">error</span> ERROR';
      } else if (status === "WARNING") {
        resultStatusBadge.classList.add("bg-[#F59E0B]/10", "text-[#F59E0B]");
        resultStatusBadge.innerHTML = '<span class="material-symbols-outlined text-[16px]" style="font-variation-settings:\'FILL\'1">warning</span> WARNING';
      } else {
        resultStatusBadge.classList.add("bg-[#d6e0f6]", "text-[#002045]");
        resultStatusBadge.innerHTML = '<span class="material-symbols-outlined text-[16px]">info</span> INFO';
      }
    }

    // Journal ID row
    if (resultJournalRow) {
      if (journalId) {
        resultJournalRow.classList.remove("hidden");
        resultSummaryTitle.textContent = journalId;
      } else {
        resultJournalRow.classList.add("hidden");
      }
    }

    resultSummaryBody.innerHTML = body;
    resultSummaryDetail.textContent = detail || "";
  }

  function showProcessingFlow(flowType) {
    if (!processingFlow || !processingFlowSteps) return;

    processingFlow.classList.remove("hidden");

    const flows = {
      "setup": [
        "Demo Data Created",
        "Customer & Accounts Provisioned",
        "Product Versions Activated",
        "Ledger Accounts Mapped"
      ],
      "payment-authorize": [
        "Request Validated",
        "Hold Created",
        "Available Balance Reserved",
        "Audit/Outbox Written"
      ],
      "payment-capture": [
        "Request Validated",
        "Hold Checked",
        "Ledger Journal Posted",
        "Audit/Outbox Written"
      ],
      "payment-void": [
        "Request Validated",
        "Hold Checked",
        "Hold Released",
        "Audit/Outbox Written"
      ],
      "transfer": [
        "Request Validated",
        "Idempotency Key Checked",
        "Ledger Journal Posted",
        "Audit/Outbox Written"
      ],
      "transfer-replay": [
        "Request Validated",
        "Idempotency Key Checked",
        "Previous Result Returned",
        "Audit/Outbox Written"
      ],
      "deposit": [
        "Request Validated",
        "Product Version Verified",
        "Ledger Journal Posted",
        "Audit/Outbox Written"
      ],
      "lending": [
        "Request Validated",
        "Contract Validated",
        "Ledger Journal Posted",
        "Audit/Outbox Written"
      ]
    };

    const steps = flows[flowType] || flows["transfer"];
    processingFlowSteps.innerHTML = steps.map((step, i) => {
      const isLast = i === steps.length - 1;
      return `
        <div class="relative">
          <div class="absolute -left-[27px] top-1 w-2 h-2 rounded-full ${isLast ? 'bg-[#002045] ring-2 ring-[#d6e3ff]' : 'bg-[#10B981]'}"></div>
          <p class="text-[14px] font-bold">${step}</p>
        </div>`;
    }).join("");
  }

  function markProgress(step, done) {
    state.demoProgress[step] = done;
    // Update check items in sidebar
    document.querySelectorAll(`[data-check="${step}"]`).forEach((el) => {
      if (done) {
        el.classList.add("text-[#10B981]");
        el.classList.remove("text-[#43474e]");
        const icon = el.querySelector(".check-icon");
        if (icon) {
          icon.textContent = "check_circle";
          icon.classList.add("text-[#10B981]");
        }
      } else {
        el.classList.remove("text-[#10B981]");
        el.classList.add("text-[#43474e]");
        const icon = el.querySelector(".check-icon");
        if (icon) {
          icon.textContent = "radio_button_unchecked";
          icon.classList.remove("text-[#10B981]");
        }
      }
    });
    // Update stepper dots in sidebar
    document.querySelectorAll(`[data-step="${step}"]`).forEach((el) => {
      const dot = el.querySelector(".stepper-dot");
      const label = el.querySelector(".stepper-label");
      if (done && dot) {
        dot.classList.add("bg-[#10B981]");
        dot.classList.remove("bg-[#dad9dd]");
      } else if (dot) {
        dot.classList.remove("bg-[#10B981]", "bg-[#002045]");
      }
      if (done && label) {
        label.classList.add("text-[#002045]", "font-bold");
        label.classList.remove("text-[#43474e]");
      }
      // For current step (next after last completed)
      if (!done && dot) {
        const prevStep = getPrevStep(step);
        if (prevStep && state.demoProgress[prevStep]) {
          dot.classList.add("bg-[#002045]", "animate-pulse");
        }
      }
    });
  }

  function getPrevStep(step) {
    const order = ["admin", "setup", "authorize", "capture", "transfer", "replay", "verify"];
    const idx = order.indexOf(step);
    return idx > 0 ? order[idx - 1] : null;
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
      amountMinor: amounts.paymentAmountMinor || 500000,
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
      amountMinor: amounts.paymentAmountMinor || 500000,
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
