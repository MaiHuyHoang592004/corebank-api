(() => {
  const ZERO_UUID = "00000000-0000-0000-0000-000000000000";

  const state = {
    authHeader: null,
    actor: "demo_admin",
    setup: null,
    holdId: ZERO_UUID,
    depositContractId: ZERO_UUID,
    loanContractId: ZERO_UUID
  };

  const authUsername = document.getElementById("auth-username");
  const authPassword = document.getElementById("auth-password");
  const authState = document.getElementById("auth-state");
  const setupOutput = document.getElementById("setup-output");
  const responseMeta = document.getElementById("response-meta");
  const responseOutput = document.getElementById("response-output");

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

  document.querySelectorAll(".tab-btn").forEach((button) => {
    button.addEventListener("click", () => activateTab(button.dataset.tab));
  });

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

  document.querySelectorAll(".action-btn").forEach((button) => {
    button.addEventListener("click", () => runAction(button.dataset.action));
  });

  refreshPayloadTemplates();

  function activateTab(tabName) {
    document.querySelectorAll(".tab-btn").forEach((tab) => {
      tab.classList.toggle("active", tab.dataset.tab === tabName);
    });
    document.querySelectorAll(".tab-body").forEach((panel) => {
      panel.classList.toggle("active", panel.dataset.panel === tabName);
    });
  }

  function saveCredentials() {
    const username = (authUsername.value || "").trim();
    const password = authPassword.value || "";
    if (!username || !password) {
      setAuthState("Provide username and password.", true);
      return;
    }
    state.actor = username;
    state.authHeader = `Basic ${btoa(`${username}:${password}`)}`;
    setAuthState(`Credentials loaded for ${username}.`);
    refreshPayloadTemplates();
  }

  async function runSetup() {
    const result = await callApi("POST", "/api/demo/setup", null);
    renderResult("POST /api/demo/setup", result);
    if (result.ok && result.body) {
      state.setup = result.body;
      if (state.setup.sampleContractIds && state.setup.sampleContractIds.maturityReadyContractId) {
        state.depositContractId = state.setup.sampleContractIds.maturityReadyContractId;
      }
      setupOutput.textContent = pretty(result.body);
      refreshPayloadTemplates();
    }
  }

  async function runAction(actionName) {
    const config = actionConfig[actionName];
    if (!config) {
      return;
    }

    const textarea = document.getElementById(config.textareaId);
    if (!textarea) {
      return;
    }

    let payload;
    try {
      payload = JSON.parse(textarea.value);
    } catch (_error) {
      renderLocalError(`Invalid JSON payload for ${actionName}.`);
      return;
    }

    const result = await callApi("POST", config.endpoint, payload);
    renderResult(`POST ${config.endpoint}`, result);

    if (!result.ok || !result.body) {
      return;
    }

    if (actionName === "payment-authorize" && result.body.holdId) {
      state.holdId = result.body.holdId;
      refreshPayloadTemplates();
    }

    if (actionName === "deposit-open" && result.body.contractId) {
      state.depositContractId = result.body.contractId;
      refreshPayloadTemplates();
    }

    if (actionName === "lending-disburse" && result.body.contractId) {
      state.loanContractId = result.body.contractId;
      refreshPayloadTemplates();
    }
  }

  async function callApi(method, path, payload) {
    const headers = {
      Accept: "application/json"
    };
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
    if (!textarea) {
      return;
    }
    textarea.value = pretty(payload);
  }

  function renderResult(label, result) {
    const metaParts = [`${label} -> ${result.status} ${result.statusText}`];
    if (result.headers.limit) {
      metaParts.push(`limit=${result.headers.limit}`);
    }
    if (result.headers.remaining) {
      metaParts.push(`remaining=${result.headers.remaining}`);
    }
    if (result.headers.retryAfter) {
      metaParts.push(`retryAfter=${result.headers.retryAfter}s`);
    }
    responseMeta.textContent = metaParts.join(" | ");
    responseMeta.classList.toggle("error", !result.ok);
    responseOutput.textContent = pretty(result.body);
  }

  function renderLocalError(message) {
    responseMeta.textContent = message;
    responseMeta.classList.add("error");
  }

  function setAuthState(message, isError = false) {
    authState.textContent = message;
    authState.classList.toggle("error", isError);
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
})();
