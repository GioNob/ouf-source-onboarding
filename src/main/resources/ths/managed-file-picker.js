'use strict';
const form = document.getElementById('upload');
const status = document.getElementById('status');
const result = document.getElementById('result');
form.addEventListener('submit', async event => {
  event.preventDefault();
  const file = document.getElementById('file').files?.[0];
  if (!file || !/\.csv$/i.test(file.name) || file.size < 1 || file.size > 10485760) {
    status.textContent = 'Scegli un CSV non vuoto di massimo 10 MiB.';
    return;
  }
  const button = document.getElementById('send');
  button.disabled = true;
  result.hidden = true;
  status.textContent = 'Caricamento in corso…';
  try {
    const session = await fetch('/trusted-human/managed-files/session', { credentials: 'same-origin', cache: 'no-store' });
    if (!session.ok) throw new Error('SESSION');
    const { csrfToken, csrfHeader } = await session.json();
    if (!csrfToken || !/^X-[A-Za-z-]+$/.test(csrfHeader)) throw new Error('SESSION');
    const digest = await crypto.subtle.digest('SHA-256', await file.arrayBuffer());
    const hash = 'sha256:' + Array.from(new Uint8Array(digest), b => b.toString(16).padStart(2, '0')).join('');
    const response = await fetch('/trusted-human/managed-files/upload', {
      method: 'POST', credentials: 'same-origin', cache: 'no-store',
      headers: { 'Content-Type': 'text/csv', 'X-Content-SHA256': hash, [csrfHeader]: csrfToken }, body: file,
    });
    if (!response.ok) throw new Error('HTTP_' + response.status);
    const uploaded = await response.json();
    const assetId = uploaded.assetId;
    if (!/^[0-9a-f-]{36}$/.test(assetId ?? '')) throw new Error('RESPONSE');
    result.textContent = 'File registrato. Asset ID: ' + assetId;
    result.hidden = false;
    status.textContent = 'Caricamento completato. Torna alla chat e comunica l’Asset ID per avviare il profilo.';
  } catch (error) {
    status.textContent = 'Caricamento non riuscito: ' + (error.message ?? 'ERRORE');
  } finally {
    button.disabled = false;
  }
});
