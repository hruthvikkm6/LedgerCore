// ==========================================================================
// LedgerCore Banking Ledger Client - SPA JavaScript Engine
// ==========================================================================

const API_BASE = "";

// Global App State
const state = {
    token: localStorage.getItem("bank_jwt_token") || null,
    username: localStorage.getItem("bank_username") || null,
    roles: JSON.parse(localStorage.getItem("bank_roles")) || [],
    accounts: [],
    activeAccountNum: null
};

// Elements cache
const dom = {
    authView: document.getElementById("auth-view"),
    mainView: document.getElementById("main-view"),
    tabLogin: document.getElementById("tab-login"),
    tabSignup: document.getElementById("tab-signup"),
    loginForm: document.getElementById("login-form"),
    signupForm: document.getElementById("signup-form"),
    logoutBtn: document.getElementById("logout-btn"),
    userInitials: document.getElementById("user-initials"),
    navUsername: document.getElementById("nav-username"),
    navRole: document.getElementById("nav-role"),
    accountsGrid: document.getElementById("accounts-grid"),
    exportStatementBtn: document.getElementById("export-statement-btn"),
    ledgerHistoryRows: document.getElementById("ledger-history-rows"),
    adminOpsSection: document.getElementById("admin-ops-section"),
    
    // Forms selectors
    tabTransfer: document.getElementById("tab-transfer"),
    tabDeposit: document.getElementById("tab-deposit"),
    tabWithdraw: document.getElementById("tab-withdraw"),
    transferForm: document.getElementById("transfer-form"),
    depositForm: document.getElementById("deposit-form"),
    withdrawForm: document.getElementById("withdraw-form"),
    
    // Dropdowns
    transferFromSel: document.getElementById("tx-transfer-from"),
    depositAccountSel: document.getElementById("tx-deposit-account"),
    withdrawAccountSel: document.getElementById("tx-withdraw-account"),

    // Admin Controls
    adminCreateAccountForm: document.getElementById("admin-create-account-form"),
    adminStatusForm: document.getElementById("admin-status-form"),
    btnFreeze: document.getElementById("adm-btn-freeze"),
    btnUnfreeze: document.getElementById("adm-btn-unfreeze"),
    btnReconcile: document.getElementById("admin-reconcile-btn"),
    reconciliationReport: document.getElementById("reconciliation-report")
};

// ==========================================================================
// Toast Alerts Engine
// ==========================================================================
function showToast(message, type = "info") {
    const container = document.getElementById("toast-container");
    const toast = document.createElement("div");
    toast.className = `toast ${type}`;
    
    let icon = "💡";
    if (type === "success") icon = "✅";
    if (type === "error") icon = "❌";

    toast.innerHTML = `
        <span>${icon} &nbsp; ${message}</span>
        <span class="toast-close">&times;</span>
    `;

    container.appendChild(toast);

    toast.querySelector(".toast-close").addEventListener("click", () => {
        toast.remove();
    });

    // Auto remove after 5 seconds
    setTimeout(() => {
        if (toast.parentNode) {
            toast.remove();
        }
    }, 5000);
}

// Helper: Generates a cryptographically secure random UUID for Idempotency Keys
function generateUUID() {
    return ([1e7]+-1e3+-4e3+-8e3+-1e11).replace(/[018]/g, c =>
        (c ^ crypto.getRandomValues(new Uint8Array(1))[0] & 15 >> c / 4).toString(16)
    );
}

// Helper: Attach Authorization Header
function getAuthHeaders(idempotencyKey = null) {
    const headers = {
        "Content-Type": "application/json",
        "Authorization": `Bearer ${state.token}`
    };
    if (idempotencyKey) {
        headers["Idempotency-Key"] = idempotencyKey;
    }
    return headers;
}

// Parse JWT claims to check roles (in case localStorage is tampered)
function parseJwtRoleBadge() {
    if (!state.roles || state.roles.length === 0) return "CUSTOMER";
    if (state.roles.includes("ROLE_ADMIN")) return "ADMINISTRATOR";
    if (state.roles.includes("ROLE_OPS")) return "OPERATIONS DESK";
    return "CUSTOMER";
}

// ==========================================================================
// API Handlers
// ==========================================================================

// Authenticate / Login
async function handleLogin(e) {
    e.preventDefault();
    const username = document.getElementById("login-username").value;
    const password = document.getElementById("login-password").value;

    try {
        const res = await fetch(`${API_BASE}/api/auth/login`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ username, password })
        });

        const data = await res.json();
        
        if (!res.ok) {
            throw new Error(data.message || "Invalid credentials.");
        }

        // Save session
        state.token = data.token;
        state.username = data.username;
        state.roles = data.roles;

        localStorage.setItem("bank_jwt_token", data.token);
        localStorage.setItem("bank_username", data.username);
        localStorage.setItem("bank_roles", JSON.stringify(data.roles));

        showToast(`Authenticated successfully! Welcome, ${data.username}`, "success");
        initApp();
    } catch (err) {
        showToast(err.message, "error");
    }
}

// Sign Up / Register
async function handleSignup(e) {
    e.preventDefault();
    const username = document.getElementById("signup-username").value;
    const email = document.getElementById("signup-email").value;
    const password = document.getElementById("signup-password").value;
    const firstName = document.getElementById("signup-firstname").value;
    const lastName = document.getElementById("signup-lastname").value;
    const roleSelected = document.getElementById("signup-role").value;

    try {
        const res = await fetch(`${API_BASE}/api/auth/signup`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
                username, password, email, firstName, lastName,
                roles: [roleSelected]
            })
        });

        const data = await res.json();

        if (!res.ok) {
            throw new Error(data.message || "Signup failed.");
        }

        showToast("Registration successful! You can now log in.", "success");
        // Switch tab back to login
        dom.tabLogin.click();
    } catch (err) {
        showToast(err.message, "error");
    }
}

// Load Accounts List
async function loadAccounts() {
    try {
        const res = await fetch(`${API_BASE}/api/accounts/my`, {
            headers: getAuthHeaders()
        });

        if (!res.ok) {
            throw new Error("Unable to fetch accounts.");
        }

        const data = await res.json();
        state.accounts = data;

        renderAccountsGrid();
        populateAccountSelects();

        // UX Improvement: Auto-select the first account if none is active to load history logs
        if (!state.activeAccountNum && data.length > 0) {
            selectAccount(data[0].accountNumber);
        } else if (state.activeAccountNum) {
            // Keep the active account highlighted and reload its history
            selectAccount(state.activeAccountNum);
        }
    } catch (err) {
        showToast(err.message, "error");
    }
}

// Load Transactions History
async function loadHistory(accountNum) {
    try {
        const res = await fetch(`${API_BASE}/api/transactions/${accountNum}/history`, {
            headers: getAuthHeaders()
        });

        if (!res.ok) {
            throw new Error("Unable to load transaction history logs.");
        }

        const data = await res.json();
        renderLedgerHistory(data);
    } catch (err) {
        showToast(err.message, "error");
    }
}

// Export PDF Statement
async function exportStatement() {
    if (!state.activeAccountNum) return;

    showToast("Compiling PDF Statement... please wait.", "info");
    try {
        const res = await fetch(`${API_BASE}/api/transactions/${state.activeAccountNum}/statement`, {
            headers: getAuthHeaders()
        });

        if (!res.ok) {
            throw new Error("PDF generation failed.");
        }

        const blob = await res.blob();
        const url = window.URL.createObjectURL(blob);
        const a = document.createElement("a");
        a.href = url;
        a.download = `statement_${state.activeAccountNum}.pdf`;
        document.body.appendChild(a);
        a.click();
        a.remove();
        window.URL.revokeObjectURL(url);
        
        showToast("Statement PDF exported successfully!", "success");
    } catch (err) {
        showToast(err.message, "error");
    }
}

// POST Transfer
async function handleTransfer(e) {
    e.preventDefault();
    const fromAccountNumber = dom.transferFromSel.value;
    const toAccountNumber = document.getElementById("tx-transfer-to").value;
    const amount = parseFloat(document.getElementById("tx-transfer-amount").value);
    const description = document.getElementById("tx-transfer-desc").value;
    
    // Auto-generate a secure random UUID idempotency key
    const idempotencyKey = generateUUID();

    try {
        const res = await fetch(`${API_BASE}/api/transactions/transfer`, {
            method: "POST",
            headers: getAuthHeaders(idempotencyKey),
            body: JSON.stringify({ fromAccountNumber, toAccountNumber, amount, description })
        });

        const data = await res.json();
        if (!res.ok) {
            throw new Error(data.message || "Transfer execution rolled back.");
        }

        showToast("Double-entry Transfer posted successfully!", "success");
        dom.transferForm.reset();
        await refreshDashboard();
    } catch (err) {
        showToast(err.message, "error");
    }
}

// POST Deposit
async function handleDeposit(e) {
    e.preventDefault();
    const accountNumber = dom.depositAccountSel.value;
    const amount = parseFloat(document.getElementById("tx-deposit-amount").value);
    const description = document.getElementById("tx-deposit-desc").value;
    
    const idempotencyKey = generateUUID();

    try {
        const res = await fetch(`${API_BASE}/api/transactions/deposit`, {
            method: "POST",
            headers: getAuthHeaders(idempotencyKey),
            body: JSON.stringify({ accountNumber, amount, description })
        });

        const data = await res.json();
        if (!res.ok) {
            throw new Error(data.message || "Deposit rejected.");
        }

        showToast("Vault Deposit posted successfully!", "success");
        dom.depositForm.reset();
        await refreshDashboard();
    } catch (err) {
        showToast(err.message, "error");
    }
}

// POST Withdrawal
async function handleWithdraw(e) {
    e.preventDefault();
    const accountNumber = dom.withdrawAccountSel.value;
    const amount = parseFloat(document.getElementById("tx-withdraw-amount").value);
    const description = document.getElementById("tx-withdraw-desc").value;
    
    const idempotencyKey = generateUUID();

    try {
        const res = await fetch(`${API_BASE}/api/transactions/withdraw`, {
            method: "POST",
            headers: getAuthHeaders(idempotencyKey),
            body: JSON.stringify({ accountNumber, amount, description })
        });

        const data = await res.json();
        if (!res.ok) {
            throw new Error(data.message || "Withdrawal rejected.");
        }

        showToast("ATM Withdrawal posted successfully!", "success");
        dom.withdrawForm.reset();
        await refreshDashboard();
    } catch (err) {
        showToast(err.message, "error");
    }
}

// Admin: Create Account
async function handleAdminCreateAccount(e) {
    e.preventDefault();
    const userId = parseInt(document.getElementById("adm-user-id").value);
    const type = document.getElementById("adm-account-type").value;
    const currency = document.getElementById("adm-currency").value;
    const initialBalance = parseFloat(document.getElementById("adm-initial-balance").value);

    try {
        const res = await fetch(`${API_BASE}/api/accounts`, {
            method: "POST",
            headers: getAuthHeaders(),
            body: JSON.stringify({ userId, type, currency, initialBalance })
        });

        const data = await res.json();
        if (!res.ok) {
            throw new Error(data.message || "Account creation failed.");
        }

        showToast(`Account ${data.accountNumber} registered successfully!`, "success");
        dom.adminCreateAccountForm.reset();
        await refreshDashboard();
    } catch (err) {
        showToast(err.message, "error");
    }
}

// Admin: Freeze/Unfreeze account
async function handleAccountStatus(action) {
    const accountNumber = document.getElementById("adm-status-account").value;
    if (!accountNumber) {
        showToast("Please enter an account number.", "error");
        return;
    }

    try {
        const res = await fetch(`${API_BASE}/api/accounts/${accountNumber}/${action}`, {
            method: "POST",
            headers: getAuthHeaders()
        });

        const data = await res.json();
        if (!res.ok) {
            throw new Error(data.message || `Account ${action} failed.`);
        }

        showToast(data.message || `Account ${action} succeeded.`, "success");
        dom.adminStatusForm.reset();
        await refreshDashboard();
    } catch (err) {
        showToast(err.message, "error");
    }
}

// Admin/Ops: Trigger Live Reconciliation Run
async function handleReconciliation() {
    showToast("Launching ledger reconciliation scan...", "info");
    dom.reconciliationReport.classList.add("hidden");

    try {
        const res = await fetch(`${API_BASE}/api/ops/reconcile`, {
            method: "POST",
            headers: getAuthHeaders()
        });

        const data = await res.ok ? await res.json() : null;
        if (!res.ok || !data) {
            const errData = await res.json();
            throw new Error(errData.message || "Reconciliation process failed.");
        }

        document.getElementById("rep-status").innerText = data.status;
        document.getElementById("rep-checked").innerText = data.totalTransactionsChecked;
        
        const anomaliesEl = document.getElementById("rep-anomalies");
        anomaliesEl.innerText = data.anomaliesFound;
        if (data.anomaliesFound > 0) {
            anomaliesEl.style.color = "var(--accent-rose)";
            anomaliesEl.style.fontWeight = "bold";
            showToast(`RECONCILIATION COMPLETED: ${data.anomaliesFound} anomalies detected!`, "error");
        } else {
            anomaliesEl.style.color = "var(--accent-emerald)";
            showToast("RECONCILIATION COMPLETED: 0 anomalies detected. Ledger is consistent!", "success");
        }

        dom.reconciliationReport.classList.remove("hidden");
    } catch (err) {
        showToast(err.message, "error");
    }
}

// Refresh Dashboard state helper
async function refreshDashboard() {
    await loadAccounts();
    await loadRegisteredUsers();
    if (state.activeAccountNum) {
        await loadHistory(state.activeAccountNum);
    }
}

// ==========================================================================
// Rendering Engine UI
// ==========================================================================

function renderAccountsGrid() {
    dom.accountsGrid.innerHTML = "";
    
    if (state.accounts.length === 0) {
        dom.accountsGrid.innerHTML = `<div class="no-accounts">No accounts active. Consult system admin to register an account.</div>`;
        dom.exportStatementBtn.disabled = true;
        return;
    }

    state.accounts.forEach(ac => {
        const card = document.createElement("div");
        card.className = `account-card glass ${ac.accountNumber === state.activeAccountNum ? "active" : ""}`;
        card.addEventListener("click", () => selectAccount(ac.accountNumber));

        card.innerHTML = `
            <div class="ac-header">
                <span class="ac-number">${ac.accountNumber}</span>
                <span class="ac-type">${ac.type}</span>
            </div>
            <div class="ac-balance">$${ac.balance.toFixed(4)}</div>
            <div class="ac-footer">
                <span>Currency: <strong>${ac.currency}</strong></span>
                <span class="status-indicator ${ac.status === 'ACTIVE' ? 'active' : 'frozen'}">${ac.status}</span>
            </div>
        `;
        dom.accountsGrid.appendChild(card);
    });
}

function selectAccount(accountNum) {
    state.activeAccountNum = accountNum;
    
    // Toggle active class on cards
    const cards = dom.accountsGrid.querySelectorAll(".account-card");
    cards.forEach(card => {
        const num = card.querySelector(".ac-number").innerText;
        if (num === accountNum) {
            card.classList.add("active");
        } else {
            card.classList.remove("active");
        }
    });

    dom.exportStatementBtn.disabled = false;
    loadHistory(accountNum);
}

function populateAccountSelects() {
    const selects = [dom.transferFromSel, dom.depositAccountSel, dom.withdrawAccountSel];
    
    selects.forEach(sel => {
        sel.innerHTML = "";
        state.accounts.forEach(ac => {
            if (ac.status === "ACTIVE") {
                const opt = document.createElement("option");
                opt.value = ac.accountNumber;
                opt.innerText = `${ac.accountNumber} - ($${ac.balance.toFixed(2)})`;
                sel.appendChild(opt);
            }
        });
    });
}

function renderLedgerHistory(entries) {
    dom.ledgerHistoryRows.innerHTML = "";

    if (entries.length === 0) {
        dom.ledgerHistoryRows.innerHTML = `<tr><td colspan="6" class="table-empty">No transaction history recorded for this account.</td></tr>`;
        return;
    }

    entries.forEach(e => {
        const row = document.createElement("tr");
        const dateStr = new Date(e.createdAt).toLocaleString();
        const isDebit = e.entryType === "DEBIT";
        const flowClass = isDebit ? "flow-debit" : "flow-credit";
        const prefix = isDebit ? "-" : "+";

        row.innerHTML = `
            <td>${dateStr}</td>
            <td><strong>#${e.transactionId}</strong></td>
            <td>${e.description}</td>
            <td><span class="ac-type">${e.entryType}</span></td>
            <td class="${flowClass}">${prefix}$${e.amount.toFixed(4)}</td>
            <td><strong>$${e.balanceAfter.toFixed(4)}</strong></td>
        `;
        dom.ledgerHistoryRows.appendChild(row);
    });
}

// Render Admin Board based on claims
function renderAdminConsole() {
    const isStaff = state.roles.includes("ROLE_ADMIN") || state.roles.includes("ROLE_OPS");
    if (isStaff) {
        dom.adminOpsSection.classList.remove("hidden");
    } else {
        dom.adminOpsSection.classList.add("hidden");
    }
}

// Load all registered users for Admin directory
async function loadRegisteredUsers() {
    const isStaff = state.roles.includes("ROLE_ADMIN") || state.roles.includes("ROLE_OPS");
    if (!isStaff) return;
    
    try {
        const res = await fetch(`${API_BASE}/api/accounts/users`, {
            headers: getAuthHeaders()
        });
        
        if (!res.ok) {
            throw new Error("Unable to fetch user list.");
        }
        
        const users = await res.json();
        renderRegisteredUsers(users);
    } catch (err) {
        console.error("Failed to load registered users:", err);
    }
}

function renderRegisteredUsers(users) {
    const rows = document.getElementById("admin-users-list-rows");
    if (!rows) return;
    
    rows.innerHTML = "";
    if (users.length === 0) {
        rows.innerHTML = `<tr><td colspan="4" class="table-empty">No users registered.</td></tr>`;
        return;
    }
    
    users.forEach(u => {
        const row = document.createElement("tr");
        
        // Clean role badge display
        const displayRole = u.roles.includes("ROLE_ADMIN") ? "ADMIN" : (u.roles.includes("ROLE_OPS") ? "OPS" : "CUSTOMER");
        const badgeClass = displayRole === "ADMIN" ? "role-badge admin" : (displayRole === "OPS" ? "role-badge ops" : "role-badge customer");
        
        row.innerHTML = `
            <td><strong>#${u.id}</strong></td>
            <td>${u.username}</td>
            <td>${u.fullName}</td>
            <td><span class="${badgeClass}">${displayRole}</span></td>
        `;
        rows.appendChild(row);
    });
}

// ==========================================================================
// Initialization & Core Switch Routing
// ==========================================================================

function initApp() {
    if (state.token) {
        // Logged In view
        dom.authView.classList.add("hidden");
        dom.mainView.classList.remove("hidden");

        // Profile meta
        dom.userInitials.innerText = state.username.substring(0, 2).toUpperCase();
        dom.navUsername.innerText = state.username;
        dom.navRole.innerText = parseJwtRoleBadge();

        renderAdminConsole();
        loadAccounts();
        loadRegisteredUsers();
    } else {
        // Auth view
        dom.authView.classList.remove("hidden");
        dom.mainView.classList.add("hidden");
    }
}

// View Switches listeners
dom.tabLogin.addEventListener("click", () => {
    dom.tabLogin.classList.add("active");
    dom.tabSignup.classList.remove("active");
    dom.loginForm.classList.add("active");
    dom.signupForm.classList.remove("active");
});

dom.tabSignup.addEventListener("click", () => {
    dom.tabSignup.classList.add("active");
    dom.tabLogin.classList.remove("active");
    dom.signupForm.classList.add("active");
    dom.loginForm.classList.remove("active");
});

// Mini Form selectors switcher
function switchActionForm(activeForm, activeTab) {
    const forms = [dom.transferForm, dom.depositForm, dom.withdrawForm];
    const tabs = [dom.tabTransfer, dom.tabDeposit, dom.tabWithdraw];

    forms.forEach(form => form.classList.remove("active"));
    tabs.forEach(tab => tab.classList.remove("active"));

    activeForm.classList.add("active");
    activeTab.classList.add("active");
}

dom.tabTransfer.addEventListener("click", () => switchActionForm(dom.transferForm, dom.tabTransfer));
dom.tabDeposit.addEventListener("click", () => switchActionForm(dom.depositForm, dom.tabDeposit));
dom.tabWithdraw.addEventListener("click", () => switchActionForm(dom.withdrawForm, dom.tabWithdraw));

// Authentication Actions listeners
dom.loginForm.addEventListener("submit", handleLogin);
dom.signupForm.addEventListener("submit", handleSignup);

dom.logoutBtn.addEventListener("click", () => {
    // Clear Local Storage
    localStorage.removeItem("bank_jwt_token");
    localStorage.removeItem("bank_username");
    localStorage.removeItem("bank_roles");

    state.token = null;
    state.username = null;
    state.roles = [];
    state.accounts = [];
    state.activeAccountNum = null;

    // Explicitly wipe sensitive DOM components to prevent stale leakage
    dom.accountsGrid.innerHTML = `<div class="no-accounts">No accounts active. Consult system admin to register an account.</div>`;
    dom.transferFromSel.innerHTML = "";
    dom.depositAccountSel.innerHTML = "";
    dom.withdrawAccountSel.innerHTML = "";
    dom.ledgerHistoryRows.innerHTML = `<tr><td colspan="6" class="table-empty">Select active account to load history logs.</td></tr>`;
    dom.exportStatementBtn.disabled = true;

    showToast("Disconnected securely from payments node.", "info");
    initApp();
});

// Payment forms submit listeners
dom.transferForm.addEventListener("submit", handleTransfer);
dom.depositForm.addEventListener("submit", handleDeposit);
dom.withdrawForm.addEventListener("submit", handleWithdraw);

// Exporter click listener
dom.exportStatementBtn.addEventListener("click", exportStatement);

// Admin forms listeners
dom.adminCreateAccountForm.addEventListener("submit", handleAdminCreateAccount);
dom.btnFreeze.addEventListener("click", () => handleAccountStatus("freeze"));
dom.btnUnfreeze.addEventListener("click", () => handleAccountStatus("unfreeze"));
dom.btnReconcile.addEventListener("click", handleReconciliation);

// Run App!
initApp();
