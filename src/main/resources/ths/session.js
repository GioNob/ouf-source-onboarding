'use strict';
const ThsSession = (() => {
  const slot = 'ouf-ths-return:' + location.pathname + location.search;
  const bindings = new Map();
  let saved, pending, actor, expired = false;
  try {
    const draft = JSON.parse(sessionStorage.getItem(slot));
    sessionStorage.removeItem(slot);
    saved = draft?.fields;
  } catch { /* Fresh review is still possible without browser storage. */ }

  // Each surface binds only editable choices, after reading the current owner revision.
  function bind(key, fields) {
    if (!actor) return;
    key = actor + ':' + key;
    for (const [previous, items] of bindings) {
      if (!items[0].isConnected || items[0] === fields[0]) bindings.delete(previous);
    }
    bindings.set(key, fields);
    const values = saved?.[key];
    if (!Array.isArray(values) || values.length !== fields.length) return;
    delete saved[key];
    fields.forEach((field, index) => {
      if (field.type === 'checkbox') field.checked = values[index] === true;
      else field.value = values[index];
      field.dispatchEvent(new Event('change', {bubbles: true}));
      field.dispatchEvent(new Event('input', {bubbles: true}));
    });
  }

  function login() {
    if (pending && Object.keys(pending).length) {
      try { sessionStorage.setItem(slot, JSON.stringify({fields: pending})); }
      catch {
        document.getElementById('ths-storage-note').hidden = false;
        pending = null; // A second explicit click can continue without a saved draft.
        return;
      }
    }
    location.assign(location.pathname + location.search + (location.search ? '&' : '?') + '__ths_login');
  }

  function expiredNotice() {
    if (expired) return;
    expired = true;
    // Snapshot before the caller clears unavailable cards or resets its controls in finally.
    pending = Object.assign(Object.create(null), saved);
    for (const [key, elements] of bindings) {
      if (elements.every(field => field.isConnected))
        pending[key] = elements.map(field => field.type === 'checkbox' ? field.checked : field.value);
    }
    const notice = document.createElement('section'), title = document.createElement('h2');
    title.textContent = 'Sessione scaduta: accedi di nuovo';
    const button = document.createElement('button');
    button.type = 'button'; button.textContent = 'Accedi di nuovo'; button.onclick = login;
    const note = document.createElement('p');
    note.id = 'ths-storage-note'; note.hidden = true;
    note.textContent = 'Il browser non può conservare le scelte. Annotale e premi di nuovo Accedi.';
    notice.setAttribute('aria-label', 'Accesso richiesto');
    notice.append(title, button, note);
    document.body.prepend(notice);
    document.querySelector('main').inert = true;
    button.focus();
  }

  async function request(url, options) {
    const response = await fetch(url, options);
    if (response.ok && (!options?.method || options.method === 'GET')) {
      const binding = response.headers.get('X-OUF-Review-Actor');
      actor = /^[a-f0-9]{64}$/.test(binding) ? binding : null;
    }
    if (response.status === 401) {
      const problem = await response.clone().json().catch(() => null);
      if (problem?.code === 'THS_SESSION_EXPIRED') expiredNotice();
    }
    return response;
  }
  return {fetch: request, bind};
})();
