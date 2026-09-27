'use strict';
const form = document.getElementById('upload');
const status = document.getElementById('status');
const result = document.getElementById('result');
const handoff = new URLSearchParams(window.location.search).get('handoff');
const handoffValid = handoff === null || /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(handoff);
form.addEventListener('submit', async event => {
  event.preventDefault();
  const file = document.getElementById('file').files?.[0];
  if (!handoffValid || !file) {
    status.textContent = 'Seleziona un file valido.';
    return;
  }
  if (file.size < 1 || file.size > 10485760) {
    status.textContent = 'Il file deve essere non vuoto e non superare 10 MiB.';
    return;
  }
  if (!/\.csv$/i.test(file.name)) {
    status.textContent = 'Formato non ancora gestito per il caricamento: ' + (file.name.split('.').pop() || 'sconosciuto').slice(0, 16) + '. Al momento è supportato CSV.';
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
    const uploadPath = '/trusted-human/managed-files/upload' + (handoff ? '?handoff=' + encodeURIComponent(handoff) : '');
    const response = await fetch(uploadPath, {
      method: 'POST', credentials: 'same-origin', cache: 'no-store',
      headers: { 'Content-Type': 'text/csv', 'X-Content-SHA256': hash, [csrfHeader]: csrfToken }, body: file,
    });
    if (!response.ok) throw new Error('HTTP_' + response.status);
    const uploaded = await response.json();
    const assetId = uploaded.assetId;
    if (!/^[0-9a-f-]{36}$/.test(assetId ?? '')) throw new Error('RESPONSE');
    result.textContent = 'File registrato. Asset ID: ' + assetId;
    result.hidden = false;
    status.textContent = handoff ? 'Caricamento completato. Torna alla chat: l’esito arriverà automaticamente.' :
      'Caricamento completato. Torna alla chat e comunica l’Asset ID per avviare il profilo.';
  } catch (error) {
    status.textContent = 'Caricamento non riuscito: ' + (error.message ?? 'ERRORE');
  } finally {
    button.disabled = false;
  }
});
