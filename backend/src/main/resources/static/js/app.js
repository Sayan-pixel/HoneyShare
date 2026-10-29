/**
 * HoneyShare — Client-Side Application Engine (ES Modules)
 * Handles resumable chunked uploads, Web Crypto SHA-256 verification,
 * authenticated sessions, inbox downloads, notifications, and quota management.
 */

const CHUNK_SIZE_BYTES = 10 * 1024 * 1024; // 10 MiB parts matching server configuration

const state = {
  token: localStorage.getItem('hs_token') || null,
  user: null,
  activeView: 'sendView',
  selectedFile: null,
  upload: {
    active: false,
    paused: false,
    transferId: null,
    sessionId: null,
    expectedSha256: null,
    chunks: [],
    currentChunkIndex: 0,
    uploadedBytes: 0,
    startTime: 0,
    abortController: null,
  }
};

// =========================================================================
// API Helper
// =========================================================================
async function api(path, options = {}) {
  const headers = { ...options.headers };
  if (state.token) {
    headers['Authorization'] = `Bearer ${state.token}`;
  }
  if (options.body && !(options.body instanceof FormData)) {
    headers['Content-Type'] = 'application/json';
  }

  const res = await fetch(path, { ...options, headers });
  
  if (res.status === 401 && state.token) {
    showAlert('Session expired. Please sign in again.', 'alert-warning');
    logout();
    throw new Error('AUTH_REQUIRED');
  }

  const isJson = res.headers.get('content-type')?.includes('application/json');
  const data = isJson ? await res.json() : await res.text();

  if (!res.ok) {
    const errorMsg = isJson && data.message ? data.message : `Request failed (${res.status})`;
    throw new Error(errorMsg);
  }

  return data;
}

// =========================================================================
// Alerts & UI Helpers
// =========================================================================
function showAlert(message, type = 'alert-danger') {
  const banner = document.getElementById('alertBanner');
  banner.className = `alert-banner ${type}`;
  banner.textContent = message;
  banner.classList.remove('hidden');
  setTimeout(() => {
    banner.classList.add('hidden');
  }, 6000);
}

function formatBytes(bytes) {
  if (bytes === 0) return '0 B';
  const k = 1024;
  const sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
}

function switchView(viewId) {
  document.querySelectorAll('.view-panel').forEach(panel => panel.classList.add('hidden'));
  document.querySelectorAll('.nav-btn').forEach(btn => {
    if (btn.dataset.view === viewId) btn.classList.add('active');
    else btn.classList.remove('active');
  });

  const target = document.getElementById(viewId);
  if (target) {
    target.classList.remove('hidden');
    state.activeView = viewId;
  }

  // Auto-refresh data on view switch
  if (viewId === 'inboxView') loadInbox();
  if (viewId === 'historyView') loadHistory();
  if (viewId === 'notificationsView') loadNotifications();
  if (viewId === 'settingsView') loadSettings();
}

// =========================================================================
// SHA-256 Calculator (Web Crypto API + Pure JS Fallback for Mobile HTTP)
// =========================================================================
async function jsSha256(blobOrFile) {
  const buffer = await blobOrFile.arrayBuffer();
  const bytes = new Uint8Array(buffer);
  function rightRotate(value, amount) { return (value >>> amount) | (value << (32 - amount)); }
  const words = [];
  const asciiBitLength = bytes.length * 8;
  const hash = [0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19];
  const k = [
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
  ];
  for (let i = 0; i < bytes.length; i++) words[i >> 2] |= bytes[i] << ((3 - (i % 4)) * 8);
  words[bytes.length >> 2] |= 0x80 << ((3 - (bytes.length % 4)) * 8);
  words[(((bytes.length + 8) >> 6) << 4) + 15] = asciiBitLength;
  const w = new Array(64);
  for (let i = 0; i < words.length; i += 16) {
    let a = hash[0], b = hash[1], c = hash[2], d = hash[3];
    let e = hash[4], f = hash[5], g = hash[6], h = hash[7];
    for (let j = 0; j < 64; j++) {
      if (j < 16) { w[j] = words[i + j] | 0; }
      else {
        const s0 = rightRotate(w[j - 15], 7) ^ rightRotate(w[j - 15], 18) ^ (w[j - 15] >>> 3);
        const s1 = rightRotate(w[j - 2], 17) ^ rightRotate(w[j - 2], 19) ^ (w[j - 2] >>> 10);
        w[j] = (w[j - 16] + s0 + w[j - 7] + s1) | 0;
      }
      const temp1 = (h + (rightRotate(e, 6) ^ rightRotate(e, 11) ^ rightRotate(e, 25)) + ((e & f) ^ (~e & g)) + k[j] + w[j]) | 0;
      const temp2 = ((rightRotate(a, 2) ^ rightRotate(a, 13) ^ rightRotate(a, 22)) + ((a & b) ^ (a & c) ^ (b & c))) | 0;
      h = g; g = f; f = e; e = (d + temp1) | 0; d = c; c = b; b = a; a = (temp1 + temp2) | 0;
    }
    hash[0] = (hash[0] + a) | 0; hash[1] = (hash[1] + b) | 0; hash[2] = (hash[2] + c) | 0; hash[3] = (hash[3] + d) | 0;
    hash[4] = (hash[4] + e) | 0; hash[5] = (hash[5] + f) | 0; hash[6] = (hash[6] + g) | 0; hash[7] = (hash[7] + h) | 0;
  }
  let res = '';
  for (let i = 0; i < 8; i++) {
    for (let j = 3; j >= 0; j--) {
      const b = (hash[i] >> (8 * j)) & 255;
      res += (b < 16 ? '0' : '') + b.toString(16);
    }
  }
  return res;
}

async function computeSha256(file, onProgress) {
  // 1. Try background Web Worker first for non-blocking UI
  if (window.Worker) {
    try {
      return await new Promise((resolve, reject) => {
        const worker = new Worker('/js/hash-worker.js');
        worker.onmessage = (e) => {
          if (e.data.type === 'progress' && onProgress) {
            onProgress(e.data.percent);
          } else if (e.data.type === 'complete') {
            worker.terminate();
            resolve(e.data.hash);
          } else if (e.data.type === 'error') {
            worker.terminate();
            reject(new Error(e.data.error));
          }
        };
        worker.onerror = (err) => {
          worker.terminate();
          reject(err);
        };
        worker.postMessage({ file: file });
      });
    } catch (workerErr) {
      console.warn('Web Worker hashing fallback to main thread:', workerErr);
    }
  }

  // 2. Main-thread Web Crypto fallback
  if (window.crypto && window.crypto.subtle) {
    const buffer = await file.arrayBuffer();
    if (onProgress) onProgress(50);
    const hashBuffer = await crypto.subtle.digest('SHA-256', buffer);
    if (onProgress) onProgress(100);
    const hashArray = Array.from(new Uint8Array(hashBuffer));
    return hashArray.map(b => b.toString(16).padStart(2, '0')).join('');
  }
  // 3. Fallback for non-secure contexts (e.g. mobile accessing LAN HTTP)
  if (onProgress) onProgress(50);
  const hash = await jsSha256(file);
  if (onProgress) onProgress(100);
  return hash;
}

// =========================================================================
// Authentication
// =========================================================================
async function checkAuth() {
  if (!state.token) {
    showAuthView();
    return;
  }

  try {
    const profile = await api('/api/v1/users/me');
    state.user = profile;
    showAppView();
    updateUnreadCount();
  } catch (err) {
    logout();
  }
}

function showAuthView() {
  document.getElementById('mainNav').classList.add('hidden');
  switchView('authView');
}

function showAppView() {
  document.getElementById('mainNav').classList.remove('hidden');
  switchView('sendView');
}

function logout() {
  if (state.token) {
    api('/api/v1/auth/logout', { method: 'POST' }).catch(() => {});
  }
  state.token = null;
  state.user = null;
  localStorage.removeItem('hs_token');
  showAuthView();
}

// =========================================================================
// Resumable Upload Engine
// =========================================================================
async function startUpload() {
  const file = state.selectedFile;
  if (!file) {
    showAlert('Please select a file to transfer.');
    return;
  }

  const recipientsRaw = document.getElementById('recipientsInput').value;
  const recipients = recipientsRaw.split(',').map(s => s.trim()).filter(Boolean);
  if (recipients.length === 0) {
    showAlert('Please enter at least one recipient email address.');
    return;
  }

  const privacyMode = document.querySelector('input[name="privacyMode"]:checked').value;

  const progressCard = document.getElementById('uploadProgressCard');
  const phaseLabel = document.getElementById('uploadPhaseLabel');
  const percentLabel = document.getElementById('uploadPercentLabel');
  const progressBar = document.getElementById('uploadProgressBar');
  const startBtn = document.getElementById('startTransferBtn');

  try {
    startBtn.disabled = true;
    progressCard.classList.remove('hidden');
    phaseLabel.textContent = 'Calculating streaming SHA-256 integrity hash...';

    // 1. Calculate SHA-256
    const sha256Hex = await computeSha256(file, (p) => {
      percentLabel.textContent = `${p}%`;
      progressBar.style.width = `${p}%`;
    });

    state.upload.expectedSha256 = sha256Hex;
    phaseLabel.textContent = 'Reserving quota & creating transfer...';

    // 2. Create Transfer
    const transfer = await api('/api/v1/transfers', {
      method: 'POST',
      body: JSON.stringify({
        originalFilename: file.name,
        fileSizeBytes: file.size,
        contentTypeHint: file.type || 'application/octet-stream',
        privacyMode: privacyMode,
        expectedSha256: sha256Hex,
        recipientEmails: recipients
      })
    });

    state.upload.transferId = transfer.transferId;

    // 3. Initiate Upload Session
    phaseLabel.textContent = 'Initiating multipart upload session...';
    const session = await api(`/api/v1/transfers/${transfer.transferId}/upload-sessions`, {
      method: 'POST',
      body: JSON.stringify({ chunkSizeBytes: CHUNK_SIZE_BYTES })
    });

    state.upload.sessionId = session.sessionId;
    const chunkSize = session.chunkSizeBytes || CHUNK_SIZE_BYTES;
    const totalChunks = session.expectedChunkCount;
    state.upload.chunks = Array.from({ length: totalChunks }, (_, i) => i);
    state.upload.currentChunkIndex = 0;
    state.upload.uploadedBytes = 0;
    state.upload.active = true;
    state.upload.paused = false;
    state.upload.startTime = Date.now();

    // 4. Upload Chunks
    await processChunks(file, session.sessionId, totalChunks, chunkSize);

  } catch (err) {
    showAlert(err.message || 'Upload failed.');
    progressCard.classList.add('hidden');
  } finally {
    startBtn.disabled = false;
  }
}

const CONCURRENT_UPLOAD_STREAMS = 3;

async function processChunks(file, sessionId, totalChunks, chunkSize = CHUNK_SIZE_BYTES) {
  const phaseLabel = document.getElementById('uploadPhaseLabel');
  const percentLabel = document.getElementById('uploadPercentLabel');
  const progressBar = document.getElementById('uploadProgressBar');
  const chunksLabel = document.getElementById('uploadChunksLabel');
  const speedLabel = document.getElementById('uploadSpeedLabel');

  state.upload.abortController = new AbortController();
  let nextChunkIndex = state.upload.currentChunkIndex;
  let completedChunks = 0;
  let uploadError = null;

  const concurrency = Math.min(CONCURRENT_UPLOAD_STREAMS, totalChunks);
  phaseLabel.textContent = `Uploading with ${concurrency} parallel streams...`;
  chunksLabel.textContent = `0 of ${totalChunks} chunks uploaded`;

  async function uploadWorker(workerId) {
    while (nextChunkIndex < totalChunks && !state.upload.paused && !uploadError) {
      const i = nextChunkIndex++;
      const startByte = i * chunkSize;
      const endByte = Math.min(startByte + chunkSize, file.size);
      const chunkBlob = file.slice(startByte, endByte);
      const partNumber = i + 1;

      try {
        let uploaded = false;

        // 1.4 Direct Presigned S3 Upload with automatic streaming fallback
        try {
          const auth = await api(`/api/v1/upload-sessions/${sessionId}/parts/${partNumber}/authorize`, {
            method: 'POST'
          });
          if (auth && auth.uploadUrl) {
            const directRes = await fetch(auth.uploadUrl, {
              method: 'PUT',
              body: chunkBlob,
              signal: state.upload.abortController.signal
            });
            if (directRes.ok) {
              const eTag = directRes.headers.get('ETag') || 'direct-part-' + partNumber;
              await api(`/api/v1/upload-sessions/${sessionId}/parts/${partNumber}/commit`, {
                method: 'POST',
                body: JSON.stringify({ partEtag: eTag })
              });
              uploaded = true;
            }
          }
        } catch (directErr) {
          // If direct presigned S3 upload hits CORS on local dev port, seamlessly fall back to server stream proxy
          console.debug(`Direct S3 upload fallback for part ${partNumber}:`, directErr);
        }

        // Reliable Server Stream Proxy fallback
        if (!uploaded) {
          const putRes = await fetch(`/api/v1/upload-sessions/${sessionId}/parts/${partNumber}`, {
            method: 'PUT',
            headers: {
              'Authorization': `Bearer ${state.token}`,
              'Content-Type': 'application/octet-stream'
            },
            body: chunkBlob,
            signal: state.upload.abortController.signal
          });

          if (!putRes.ok) {
            throw new Error(`Failed to upload chunk ${partNumber}: HTTP ${putRes.status}`);
          }
        }

        completedChunks++;
        state.upload.uploadedBytes += chunkBlob.size;
        state.upload.currentChunkIndex = Math.max(state.upload.currentChunkIndex, i);

        // Update UI
        const percent = Math.min(100, Math.round((state.upload.uploadedBytes / file.size) * 100));
        percentLabel.textContent = `${percent}%`;
        progressBar.style.width = `${percent}%`;
        chunksLabel.textContent = `${completedChunks} of ${totalChunks} chunks uploaded (${concurrency}x parallel)`;

        const elapsedSec = (Date.now() - state.upload.startTime) / 1000;
        if (elapsedSec > 0) {
          const mbps = (state.upload.uploadedBytes / (1024 * 1024)) / elapsedSec;
          speedLabel.textContent = `${mbps.toFixed(1)} MB/s`;
        }
      } catch (err) {
        if (!state.upload.paused && !uploadError) {
          uploadError = err;
          state.upload.abortController.abort();
        }
        throw err;
      }
    }
  }

  // Launch parallel workers
  const workers = Array.from({ length: concurrency }, (_, id) => uploadWorker(id));
  await Promise.all(workers);

  if (state.upload.paused) {
    phaseLabel.textContent = 'Upload paused.';
    return;
  }

  // 5. Complete multipart assembly
  phaseLabel.textContent = 'Verifying storage checksum and finalizing transfer...';
  const complete = await api(`/api/v1/upload-sessions/${sessionId}/complete`, {
    method: 'POST',
    body: JSON.stringify({ expectedSha256: state.upload.expectedSha256 })
  });

  state.upload.active = false;
  phaseLabel.textContent = 'Transfer stored and verified! Available to recipients.';
  showAlert('Transfer completed successfully!', 'alert-success');
  resetFileSelection();
  loadHistory();
}

function resetFileSelection() {
  state.selectedFile = null;
  document.getElementById('fileInput').value = '';
  document.getElementById('selectedFileInfo').classList.add('hidden');
  document.querySelector('.drop-prompt').classList.remove('hidden');
  document.getElementById('uploadProgressCard').classList.add('hidden');
}

// =========================================================================
// Recipient Inbox & Download Engine
// =========================================================================
async function loadInbox() {
  const container = document.getElementById('inboxList');
  container.innerHTML = '<p class="empty-state">Loading incoming transfers...</p>';

  try {
    const transfers = await api('/api/v1/inbox');
    if (!transfers || transfers.length === 0) {
      container.innerHTML = '<p class="empty-state">Your inbox is empty. No incoming files.</p>';
      return;
    }

    container.innerHTML = transfers.map(t => {
      const isDownloadable = ['STORED_AND_VERIFIED', 'AVAILABLE_TO_RECIPIENT'].includes(t.transferState);
      const isFailed = ['FAILED', 'CANCELLED', 'EXPIRED', 'DELETED'].includes(t.transferState);
      const expiryText = t.expiresAt ? new Date(t.expiresAt).toLocaleString() : (isFailed ? 'Transfer ' + t.transferState.toLowerCase() : 'Upload in progress');
      const badgeClass = isDownloadable ? 'status-active' : (isFailed ? 'status-danger' : 'status-uploading');
      return `
      <div class="transfer-item">
        <div class="transfer-meta">
          <h4>${escapeHtml(t.originalFilename)}</h4>
          <div class="meta-sub">
            <span>From: ${escapeHtml(t.senderId)}</span>
            <span>&bull;</span>
            <span>Size: ${formatBytes(t.fileSizeBytes)}</span>
            <span>&bull;</span>
            <span>Expires: ${escapeHtml(expiryText)}</span>
            <span>&bull;</span>
            <span class="badge ${badgeClass}">${escapeHtml(t.transferState)}</span>
          </div>
        </div>
        <div class="transfer-actions">
          ${isDownloadable ? `
            <button class="btn btn-primary btn-sm" onclick="window.downloadTransfer('${t.transferId}', '${escapeHtml(t.originalFilename)}')">
              Download File
            </button>
          ` : `
            <span class="badge ${badgeClass}">${isFailed ? escapeHtml(t.transferState) : 'Upload in progress'}</span>
          `}
        </div>
      </div>
    `}).join('');
  } catch (err) {
    container.innerHTML = `<p class="empty-state">Failed to load inbox: ${escapeHtml(err.message)}</p>`;
  }
}

window.downloadTransfer = async function(transferId, filename) {
  showAlert(`Initiating secure download session for ${filename}...`, 'alert-warning');

  try {
    // 1. Authorize download session
    const auth = await api(`/api/v1/transfers/${transferId}/download-sessions`, {
      method: 'POST'
    });

    const sessionId = auth.sessionId;
    // Download via direct streaming endpoint to avoid CORS issues
    const streamUrl = `/api/v1/download-sessions/${sessionId}/stream`;

    const res = await fetch(streamUrl, {
      headers: { 'Authorization': `Bearer ${state.token}` }
    });
    if (!res.ok) throw new Error(`Download failed: HTTP ${res.status}`);

    const blob = await res.blob();

    // 4. Trigger browser save immediately
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    setTimeout(() => URL.revokeObjectURL(url), 2000);

    // 5. Client-side SHA-256 verification (Web Crypto or JS Fallback)
    let hashHex = null;
    try {
      if (window.crypto && window.crypto.subtle) {
        const buffer = await blob.arrayBuffer();
        const hashBuffer = await crypto.subtle.digest('SHA-256', buffer);
        hashHex = Array.from(new Uint8Array(hashBuffer)).map(b => b.toString(16).padStart(2, '0')).join('');
      } else {
        hashHex = await jsSha256(blob);
      }
    } catch (hashErr) {
      console.warn('Client-side verification skipped on this context:', hashErr);
    }

    // 6. Acknowledge completion
    await api(`/api/v1/download-sessions/${sessionId}/complete`, {
      method: 'POST',
      body: JSON.stringify({ verifiedSha256: hashHex })
    });

    showAlert(`Download verified & saved successfully: ${filename}`, 'alert-success');
    loadInbox();
  } catch (err) {
    showAlert(`Download failed: ${err.message}`);
  }
};

// =========================================================================
// History & Durable Receipts
// =========================================================================
async function loadHistory() {
  const container = document.getElementById('historyList');
  container.innerHTML = '<p class="empty-state">Loading transfers...</p>';

  try {
    const list = await api('/api/v1/transfers/sent');
    if (!list || list.length === 0) {
      container.innerHTML = '<p class="empty-state">No sent transfers yet.</p>';
      return;
    }

    container.innerHTML = list.map(t => `
      <div class="transfer-item">
        <div class="transfer-meta">
          <h4>${escapeHtml(t.originalFilename)}</h4>
          <div class="meta-sub">
            <span>Size: ${formatBytes(t.fileSizeBytes)}</span>
            <span>&bull;</span>
            <span>Mode: ${t.privacyMode}</span>
            <span>&bull;</span>
            <span>Created: ${new Date(t.createdAt).toLocaleDateString()}</span>
          </div>
        </div>
        <div class="transfer-actions">
          <span class="status-badge ${statusClass(t.state)}">${t.state}</span>
          ${t.state === 'STORED_AND_VERIFIED' ? `
            <button class="btn btn-secondary btn-sm" onclick="window.getDurableReceipt('${t.transferId}')">
              Durable Receipt
            </button>
          ` : ''}
          ${['PENDING', 'UPLOADING', 'STORED_AND_VERIFIED'].includes(t.state) ? `
            <button class="btn btn-danger btn-sm" onclick="window.cancelTransfer('${t.transferId}')">
              Cancel
            </button>
          ` : ''}
        </div>
      </div>
    `).join('');
  } catch (err) {
    container.innerHTML = `<p class="empty-state">Failed to load history: ${escapeHtml(err.message)}</p>`;
  }
}

window.getDurableReceipt = async function(transferId) {
  try {
    const receipt = await api(`/api/v1/transfers/${transferId}/sender-deletion-receipt`, {
      method: 'POST'
    });
    alert(`HoneyShare Cryptographic Durable Receipt:\n\nTransfer ID: ${receipt.transferId}\nState: ${receipt.state}\nSHA-256: ${receipt.storedSha256}\nStorage Object: ${receipt.storageObjectKey}\nVerified At: ${receipt.verifiedAt}\n\nSafe to delete sender local file copy.`);
  } catch (err) {
    showAlert(err.message);
  }
};

window.cancelTransfer = async function(transferId) {
  if (!confirm('Are you sure you want to cancel this transfer and release its quota?')) return;
  try {
    await api(`/api/v1/transfers/${transferId}/cancel`, { method: 'POST' });
    showAlert('Transfer cancelled.');
    loadHistory();
    loadSettings();
  } catch (err) {
    showAlert(err.message);
  }
};

function statusClass(state) {
  switch (state) {
    case 'STORED_AND_VERIFIED': return 'status-verified';
    case 'UPLOADING': return 'status-uploading';
    case 'PENDING': return 'status-pending';
    case 'EXPIRED': return 'status-expired';
    case 'CANCELLED': return 'status-cancelled';
    default: return '';
  }
}

// =========================================================================
// Notifications
// =========================================================================
async function loadNotifications() {
  const container = document.getElementById('notificationList');
  try {
    const items = await api('/api/v1/notifications?page=0&size=50');
    if (!items || items.length === 0) {
      container.innerHTML = '<p class="empty-state">No notifications.</p>';
      return;
    }

    container.innerHTML = items.map(n => `
      <div class="notification-item ${n.read ? 'read' : 'unread'}">
        <div class="notif-content">
          <strong>${escapeHtml(n.title)}</strong>
          <p>${escapeHtml(n.message)}</p>
          <small class="hint">${new Date(n.createdAt).toLocaleString()}</small>
        </div>
        ${!n.read ? `
          <button class="btn btn-secondary btn-sm" onclick="window.markRead('${n.id}')">Mark Read</button>
        ` : ''}
      </div>
    `).join('');
  } catch (err) {
    container.innerHTML = `<p class="empty-state">Failed to load notifications: ${escapeHtml(err.message)}</p>`;
  }
}

window.markRead = async function(id) {
  await api(`/api/v1/notifications/${id}/read`, { method: 'POST' });
  loadNotifications();
  updateUnreadCount();
};

async function updateUnreadCount() {
  try {
    const data = await api('/api/v1/notifications/unread-count');
    const badge = document.getElementById('notifyBadge');
    if (data.unreadCount > 0) {
      badge.textContent = data.unreadCount;
      badge.classList.remove('hidden');
    } else {
      badge.classList.add('hidden');
    }
  } catch (err) {}
}

// =========================================================================
// Settings, Quota & Devices
// =========================================================================
async function loadSettings() {
  try {
    // 1. Quota
    const quota = await api('/api/v1/users/me/quota');
    const limit = quota.limitBytes || (200 * 1024 * 1024 * 1024);
    const storedPct = Math.min(100, (quota.storedBytes / limit) * 100);
    const reservedPct = Math.min(100 - storedPct, (quota.reservedBytes / limit) * 100);

    document.getElementById('quotaStoredBar').style.width = `${storedPct}%`;
    document.getElementById('quotaReservedBar').style.width = `${reservedPct}%`;

    document.getElementById('quotaStoredLabel').textContent = `${formatBytes(quota.storedBytes)} Stored`;
    document.getElementById('quotaReservedLabel').textContent = `${formatBytes(quota.reservedBytes)} Reserved`;
    document.getElementById('quotaRemainingLabel').textContent = `${formatBytes(quota.remainingBytes)} Available`;

    // 2. Preferences
    const prefs = await api('/api/v1/users/me/preferences');
    document.getElementById('prefEmail').checked = prefs.emailEnabled;
    document.getElementById('prefPush').checked = prefs.pushEnabled;
    document.getElementById('prefInApp').checked = prefs.inAppEnabled;

    // 3. Profile
    const profile = await api('/api/v1/users/me');
    document.getElementById('userProfileInfo').innerHTML = `
      <div><strong>User ID:</strong> <code>${profile.id}</code></div>
      <div><strong>Email:</strong> ${escapeHtml(profile.email)} (${profile.emailVerified ? 'Verified' : 'Unverified'})</div>
      <div><strong>Role:</strong> ${profile.role}</div>
      <div><strong>Two-Factor Authentication:</strong> ${profile.mfaEnabled ? 'Enabled' : 'Disabled'}</div>
      <div><strong>Member Since:</strong> ${new Date(profile.createdAt).toLocaleDateString()}</div>
    `;

    // 4. Devices
    const devices = await api('/api/v1/users/me/devices');
    const devicesContainer = document.getElementById('devicesList');
    if (!devices || devices.length === 0) {
      devicesContainer.innerHTML = '<p class="empty-state">No devices registered. Devices are enrolled automatically when using HoneyShare desktop or mobile clients.</p>';
    } else {
      devicesContainer.innerHTML = devices.map(d => `
        <div class="device-item">
          <div>
            <strong>${escapeHtml(d.deviceName)}</strong> (${d.platform})
            <div class="meta-sub">
              <span>Registered: ${new Date(d.registeredAt).toLocaleDateString()}</span>
              <span>&bull;</span>
              <span>Status: ${d.revoked ? 'Revoked' : 'Active'}</span>
            </div>
          </div>
          ${!d.revoked ? `
            <button class="btn btn-outline btn-sm" onclick="window.revokeDevice('${d.id}')">Revoke</button>
          ` : ''}
        </div>
      `).join('');
    }
  } catch (err) {
    showAlert(err.message);
  }
}

window.revokeDevice = async function(deviceId) {
  if (!confirm('Revoke this device? It will immediately lose access to wrapped E2E keys.')) return;
  try {
    await api(`/api/v1/users/me/devices/${deviceId}/revoke`, { method: 'POST' });
    showAlert('Device revoked.');
    loadSettings();
  } catch (err) {
    showAlert(err.message);
  }
};

// =========================================================================
// Event Listeners & Initialization
// =========================================================================
function escapeHtml(str) {
  if (!str) return '';
  return str.replace(/[&<>"']/g, m => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
  })[m]);
}

document.addEventListener('DOMContentLoaded', () => {
  // Navigation
  document.querySelectorAll('.nav-btn[data-view]').forEach(btn => {
    btn.addEventListener('click', () => switchView(btn.dataset.view));
  });

  document.getElementById('logoutBtn').addEventListener('click', logout);

  // Auth Tabs
  document.getElementById('tabLogin').addEventListener('click', () => {
    document.getElementById('tabLogin').classList.add('active');
    document.getElementById('tabRegister').classList.remove('active');
    document.getElementById('loginForm').classList.remove('hidden');
    document.getElementById('registerForm').classList.add('hidden');
  });

  document.getElementById('tabRegister').addEventListener('click', () => {
    document.getElementById('tabRegister').classList.add('active');
    document.getElementById('tabLogin').classList.remove('active');
    document.getElementById('registerForm').classList.remove('hidden');
    document.getElementById('loginForm').classList.add('hidden');
  });

  // Login Submit
  document.getElementById('loginForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    const email = document.getElementById('loginEmail').value.trim();
    const password = document.getElementById('loginPassword').value;
    const mfaCode = document.getElementById('loginMfaCode').value.trim();

    try {
      const res = await api('/api/v1/auth/login', {
        method: 'POST',
        body: JSON.stringify({ email, password, mfaCode: mfaCode || null })
      });

      if (res.mfaRequired) {
        document.getElementById('mfaGroup').classList.remove('hidden');
        showAlert('Two-factor authentication required. Please enter your code.', 'alert-warning');
        return;
      }

      state.token = res.sessionToken;
      localStorage.setItem('hs_token', res.sessionToken);
      await checkAuth();
      showAlert('Signed in successfully.', 'alert-success');
    } catch (err) {
      showAlert(err.message);
    }
  });

  // Register Submit
  document.getElementById('registerForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    const token = document.getElementById('regToken').value.trim();
    const email = document.getElementById('regEmail').value.trim();
    const password = document.getElementById('regPassword').value;
    const confirm = document.getElementById('regPasswordConfirm').value;

    if (password !== confirm) {
      showAlert('Passwords do not match.');
      return;
    }

    try {
      await api('/api/v1/auth/register', {
        method: 'POST',
        body: JSON.stringify({ invitationToken: token, email, password })
      });

      showAlert('Account registered! In development, check console for email verification link, then sign in.', 'alert-success');
      document.getElementById('tabLogin').click();
    } catch (err) {
      showAlert(err.message);
    }
  });

  // Drag and Drop Zone
  const dropZone = document.getElementById('dropZone');
  const fileInput = document.getElementById('fileInput');

  dropZone.addEventListener('dragover', (e) => {
    e.preventDefault();
    dropZone.classList.add('drag-over');
  });

  dropZone.addEventListener('dragleave', () => dropZone.classList.remove('drag-over'));

  dropZone.addEventListener('drop', (e) => {
    e.preventDefault();
    dropZone.classList.remove('drag-over');
    if (e.dataTransfer.files && e.dataTransfer.files[0]) {
      handleFileSelected(e.dataTransfer.files[0]);
    }
  });

  fileInput.addEventListener('change', (e) => {
    if (e.target.files && e.target.files[0]) {
      handleFileSelected(e.target.files[0]);
    }
  });

  document.getElementById('clearFileBtn').addEventListener('click', resetFileSelection);

  function handleFileSelected(file) {
    state.selectedFile = file;
    document.getElementById('fileName').textContent = file.name;
    document.getElementById('fileSize').textContent = formatBytes(file.size);
    document.querySelector('.drop-prompt').classList.add('hidden');
    document.getElementById('selectedFileInfo').classList.remove('hidden');
  }

  // Transfer Form Submit
  document.getElementById('transferForm').addEventListener('submit', (e) => {
    e.preventDefault();
    startUpload();
  });

  // Upload Controls (Pause, Resume, Cancel)
  document.getElementById('pauseUploadBtn').addEventListener('click', () => {
    state.upload.paused = true;
    if (state.upload.abortController) state.upload.abortController.abort();
    document.getElementById('pauseUploadBtn').classList.add('hidden');
    document.getElementById('resumeUploadBtn').classList.remove('hidden');
  });

  document.getElementById('resumeUploadBtn').addEventListener('click', () => {
    state.upload.paused = false;
    document.getElementById('resumeUploadBtn').classList.add('hidden');
    document.getElementById('pauseUploadBtn').classList.remove('hidden');
    processChunks(state.selectedFile, state.upload.sessionId, state.upload.chunks.length);
  });

  document.getElementById('cancelUploadBtn').addEventListener('click', async () => {
    if (!confirm('Cancel this active upload?')) return;
    state.upload.paused = true;
    if (state.upload.abortController) state.upload.abortController.abort();
    if (state.upload.transferId) {
      await api(`/api/v1/transfers/${state.upload.transferId}/cancel`, { method: 'POST' }).catch(() => {});
    }
    resetFileSelection();
    showAlert('Upload cancelled.');
  });

  // Refresh Buttons
  document.getElementById('refreshInboxBtn').addEventListener('click', loadInbox);
  document.getElementById('refreshHistoryBtn').addEventListener('click', loadHistory);
  document.getElementById('markAllReadBtn').addEventListener('click', async () => {
    await api('/api/v1/notifications/read-all', { method: 'POST' });
    loadNotifications();
    updateUnreadCount();
  });

  // Save Preferences
  document.getElementById('savePrefsBtn').addEventListener('click', async () => {
    try {
      await api('/api/v1/users/me/preferences', {
        method: 'PATCH',
        body: JSON.stringify({
          emailEnabled: document.getElementById('prefEmail').checked,
          pushEnabled: document.getElementById('prefPush').checked,
          inAppEnabled: document.getElementById('prefInApp').checked
        })
      });
      showAlert('Preferences saved.', 'alert-success');
    } catch (err) {
      showAlert(err.message);
    }
  });

  // Initial Auth Check
  checkAuth();
});
