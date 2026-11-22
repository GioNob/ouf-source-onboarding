'use strict';
(() => {
  const $ = id => document.getElementById(id);
  const flow = location.pathname.split('/')[2];
  const semantic = flow === 'semantic';
  const params = new URLSearchParams(location.search);
  const ids = (params.get('challenges') || params.get('challenge') || '').split(',');
  const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
  const cards = new Map();
  let busy = false;
  const status = (text, error = false) => { $('status').textContent = text; $('status').classList.toggle('error', error); };
  function element(tag, value, parent) { const e = document.createElement(tag); e.textContent = String(value ?? '—'); parent.append(e); return e; }
  function detail(label, value, parent) { const e = document.createElement('details'); element('summary', label, e); element('pre', JSON.stringify(value, null, 2), e); parent.append(e); }
  function sourceLink(parent, label, value) {
    if (!value) return;
    let url;
    try { url = new URL(value); } catch { /* Preserve non-web identifiers as text. */ }
    if (url && ['https:', 'http:'].includes(url.protocol) && !url.username && !url.password) {
      const link = element('a', label, parent);
      link.href = url.href; link.target = '_blank'; link.rel = 'noopener noreferrer';
    } else element('span', label, parent);
  }
  function semanticSummary(card, article) {
    const types = {CLASS:'della classe', PROPERTY:'della proprietà', RELATIONSHIP:'della relazione',
      DATATYPE:'del tipo strutturato', VALUE_MAP:'della mappa di valori', VOCABULARY:'del vocabolario',
      CONCEPT:'del concetto', ONTOLOGY:'dell’ontologia'};
    const source = card.adoptionProvenance || {};
    const label = card.label_json?.it || card.local_name || card.semantic_id;
    const english = card.label_json?.en;
    const open = card.status === 'OPEN' && !expired(card);
    const nativeType = !card.adoptionProvenance &&
      {DATATYPE:'il tipo strutturato', VALUE_MAP:'la mappa di valori'}[card.artifact_type];
    const heading = nativeType
      ? (open ? 'Stai approvando ' + nativeType : 'Richiesta di approvazione ' + types[card.artifact_type])
      : (open ? 'Stai approvando l’adozione' : 'Richiesta di adozione') + ' ' + (types[card.artifact_type] || 'della definizione');
    element('h2', `${heading} «${label}»${english ? ' (' + english + ')' : ''}`, article);
    const facts = document.createElement('dl');
    const fact = (name, value) => { element('dt', name, facts); return element('dd', value, facts); };
    const iri = source.canonicalUri || card.semantic_id;
    const id = fact('Identificativo (IRI)', '');
    sourceLink(id, iri, iri);
    if (source.sourceLocation && source.sourceLocation !== iri) {
      id.append(' · '); sourceLink(id, 'Consulta la fonte', source.sourceLocation);
    }
    if (card.semantic_id && card.semantic_id !== iri) fact('Identificativo nel registro', card.semantic_id);
    const poi = /^https?:\/\/w3id\.org\/italia\/onto\/POI\/?$/.test(card.namespace);
    fact('Ontologia di provenienza (namespace)', poi ? `Punti di interesse (POI) · ${card.namespace}` : card.namespace);
    const publisher = source.publisher;
    const agid = /^https?:\/\/spcdata\.digitpa\.gov\.it\/Amministrazione\/agid\/?$/i.test(publisher);
    fact('Editore (publisher)', agid ? `Agenzia per l’Italia Digitale (AgID) · ${publisher}` : publisher);
    if (card.authority_ref && card.authority_ref !== publisher) fact('Autorità di riferimento', card.authority_ref);
    fact('Descrizione', card.description_json?.it || card.description_json?.en ||
      card.definition_json?.description?.it || 'Descrizione non disponibile nella fonte.');
    if (source.license) fact('Licenza', source.license);
    fact('Versione semantica', card.semantic_version); fact('Revisione', card.revision_no);
    const date = new Date(card.expires_at);
    fact('Scadenza della richiesta', Number.isFinite(date.valueOf()) ? date.toLocaleString('it-IT', {timeZoneName:'short'}) : card.expires_at);
    article.append(facts);
    if (card.artifact_type === 'DATATYPE' && card.datatypeReview) datatypeSummary(card.datatypeReview, article);
    if (card.artifact_type === 'VALUE_MAP' && hasValueMapReview(card)) valueMapSummary(card.valueMapReview, article);
    const warnings = [];
    if (card.artifact_type === 'VALUE_MAP' && !hasValueMapReview(card)) warnings.push('Dettagli della mappa di valori non disponibili. Ricarica la richiesta per poterla approvare.');
    if (source.licenseWarning || (!nativeType && !source.license)) warnings.push(source.licenseWarning || 'Licenza non indicata nella fonte.');
    for (const finding of card.validationFindings || []) {
      warnings.push(typeof finding === 'string' ? finding : finding.message || finding.code || JSON.stringify(finding));
    }
    if (warnings.length) {
      const section = document.createElement('section'); section.className = 'warnings';
      element('h3', 'Avvisi da esaminare', section);
      const list = document.createElement('ul');
      for (const warning of warnings) element('li', warning, list);
      section.append(list); article.append(section);
    }
    element('h3', 'Cosa comporta l’approvazione', article);
    element('p', 'Approvi questa revisione. Con il successivo comando «Pubblica» la definizione diventa pubblicata e utilizzabile nei mapping delle fonti dell’ente. Non modifica dati esistenti e può essere deprecata in seguito.', article);
  }
  function hasValueMapReview(card) {
    const review = card.valueMapReview;
    return !!review?.name && !!review.targetVocabulary?.semanticId && !!review.targetVocabulary?.publicationSetId
      && Array.isArray(review.entries) && review.entries.length > 0 && review.entryCount === review.entries.length
      && review.entries.every(entry => typeof entry?.code === 'string' && typeof entry.concept === 'string');
  }
  function valueMapSummary(review, article) {
    const section = element('section', '', article);
    section.className = 'value-map-review';
    element('h3', review.name, section);
    const facts = element('dl', '', section);
    const fact = (name, value) => { element('dt', name, facts); element('dd', value, facts); };
    const vocabulary = review.targetVocabulary;
    fact('Vocabolario di destinazione', vocabulary.label || vocabulary.semanticId);
    fact('Identificativo (IRI)', vocabulary.semanticId);
    fact('Set di pubblicazione', vocabulary.publicationSetId);
    fact('Numero di voci', review.entryCount);
    if (review.sourceColumn?.fieldName) {
      fact('Colonna sorgente', review.sourceColumn.fieldName);
      if (review.sourceColumn.sourceId) fact('Identificativo della fonte', review.sourceColumn.sourceId);
    }

    const tableBody = (title, headings, parent) => {
      const region = element('div', '', parent);
      region.className = 'field-scroll value-map-scroll';
      region.tabIndex = 0;
      region.setAttribute('role', 'region');
      region.setAttribute('aria-label', title);
      const table = element('table', '', region);
      element('caption', title, table);
      const header = element('tr', '', element('thead', '', table));
      for (const heading of headings) element('th', heading, header).scope = 'col';
      return element('tbody', '', table);
    };
    const entries = tableBody('Codici e concetti associati', ['Codice', 'Concetto'], section);
    for (const entry of review.entries) {
      const row = element('tr', '', entries);
      element('th', entry.code, row).scope = 'row';
      const concept = element('td', entry.conceptLabel || entry.concept, row);
      if (entry.conceptLabel && entry.conceptLabel !== entry.concept) element('small', entry.concept, concept);
    }
    if (!review.changes) return;
    element('h4', 'Differenze rispetto alla versione precedente', section);
    const {added = [], removed = [], changed = []} = review.changes;
    element('p', `${added.length} aggiunti · ${removed.length} rimossi · ${changed.length} modificati`, section);
    if (added.length + removed.length + changed.length === 0) return;
    const changes = tableBody('Modifiche ai codici', ['Codice', 'Modifica'], section);
    const changeRow = (code, change) => {
      const row = element('tr', '', changes);
      element('th', code, row).scope = 'row';
      return element('td', change, row);
    };
    for (const code of added) changeRow(code, 'Aggiunto');
    for (const code of removed) changeRow(code, 'Rimosso');
    for (const {code, from, to} of changed) {
      const cell = changeRow(code, 'Modificato');
      element('small', 'Prima · ' + from, cell);
      element('small', 'Dopo · ' + to, cell);
    }
  }
  function datatypeSummary(review, article) {
    const title = 'Componenti del tipo strutturato';
    const scroll = element('div', '', article);
    scroll.className = 'field-scroll datatype-components';
    scroll.tabIndex = 0;
    scroll.setAttribute('role', 'region');
    scroll.setAttribute('aria-label', title);
    const table = element('table', '', scroll);
    element('caption', title, table);
    const header = element('tr', '', element('thead', '', table));
    for (const title of ['Nome', 'Tipo', 'Cardinalità', 'Obbligatorio', 'Vincoli']) {
      element('th', title, header).scope = 'col';
    }
    const body = element('tbody', '', table);
    for (const c of review.componenti) {
      const row = element('tr', '', body);
      element('th', c.etichetta ? c.nome + ' · ' + c.etichetta : c.nome, row).scope = 'row';
      for (const value of [c.tipo, c.cardinalità, c.obbligatorio, c.vincoli]) element('td', value, row);
    }
    element('h3', 'Differenze rispetto alla versione precedente', article);
    element('p', review.versionePrecedente, article);
    if (!review.differenze.length && review.versionePrecedente !== 'Prima pubblicazione') {
      element('p', 'Nessuna differenza.', article);
    }
    for (const change of review.differenze) {
      element('h4', change.campo + ' · ' + change.modifica, article);
      for (const [key, title] of [['prima', 'Prima'], ['dopo', 'Dopo']]) {
        if (Object.prototype.hasOwnProperty.call(change, key)) {
          element('p', title + ' · ' + datatypeChangeText(change[key]), article);
        }
      }
    }
  }
  function datatypeChangeText(value) {
    if (Array.isArray(value)) return value.join(' → ');
    if (value === null || typeof value !== 'object') return String(value);
    if (!Object.hasOwn(value, 'cardinality')) return Object.entries(value).map(([language,label]) => language + ' · ' + label).join(', ');
    const constraints = {minInclusive:'Minimo incluso',maxInclusive:'Massimo incluso',maxLength:'Lunghezza massima',vocabularyRef:'Vocabolario'};
    return [value.name, typeNames[value.type] || value.type, value.cardinality === 'MANY' ? 'Più valori' : 'Un valore',
      value.required ? 'Obbligatorio' : 'Facoltativo', value.labels && datatypeChangeText(value.labels),
      ...Object.entries(value.constraints || {}).map(([key,bound]) => constraints[key] + ' ' + bound)].filter(Boolean).join(' · ');
  }
  const unavailable = 'non disponibile';
  const accessNames = {OPEN:'pubblico', PERSONAL:'dato personale', SENSITIVE:'dato sensibile', RESTRICTED:'accesso riservato', ANONYMOUS:'dato anonimo'};
  const limitNames = {maxRows:'Righe massime', maxColumns:'Colonne massime', maxCellChars:'Caratteri massimi per cella'};
  const sectionNames = {FIELD:'Campo', IDENTITY:'Identità', SEMANTIC:'Riferimento semantico', LIMITS:'Limite', COMPOSITION:'Composizione', DATATYPE:'Tipo strutturato'};
  const typeNames = {string:'testo', integer:'numero intero', decimal:'numero decimale', date:'data', datetime:'data e ora',
    geometry:'geometria', concept:'concetto di vocabolario', boolean:'valore vero o falso'};
  const readerRules = {
    IDENTITY:'Mantiene il testo originale, anche quando è vuoto.',
    TEXT_NFC_TRIM_V1:'Uniforma i caratteri Unicode in forma NFC e rimuove gli spazi agli estremi.',
    INTEGER_V1:'Legge un numero intero di sole cifre con segno facoltativo.',
    INTEGER_GROUP_DOT_V1:'Legge un numero intero con il punto tra i gruppi di tre cifre.',
    DECIMAL_DOT_V1:'Legge un numero con il punto decimale.',
    DECIMAL_COMMA_V1:'Legge un numero con la virgola decimale.',
    DECIMAL_GROUP_DOT_COMMA_V1:'Legge un numero con punti per le migliaia e virgola per i decimali.',
    DECIMAL_GROUP_COMMA_DOT_V1:'Legge un numero con virgole per le migliaia e punto per i decimali.',
    BOOLEAN_IT_V1:'Legge come vero sì, si, s, vero, v, true o 1 e come falso no, n, falso, f, false o 0. Ignora maiuscole e spazi esterni.',
    DATE_ISO_V1:'Legge una data valida nel formato anno-mese-giorno.',
    DATE_DMY_V1:'Legge una data valida nel formato giorno/mese/anno.',
    DATE_DMY_DASH_V1:'Legge una data valida nel formato giorno-mese-anno.',
    DATETIME_ISO_OFFSET_V1:'Legge data e ora ISO con scarto orario esplicito e le riporta al tempo universale UTC. I secondi sono facoltativi, con al massimo nove cifre decimali.',
    DATETIME_LOCAL_ROME_V1:'Interpreta anno-mese-giorno e ora locale senza scarto nel fuso di Roma. I secondi sono facoltativi. Al cambio di ora rifiuta gli orari inesistenti o ambigui.',
    GEOJSON_V1:'Legge una geometria GeoJSON in EPSG:4326.',
    WKT_V1:'Legge una geometria WKT 2D o Z nel sistema di coordinate dichiarato. Le ordinate di misura M e ZM non sono ammesse',
    VALUE_MAP_V1:'Rimuove gli spazi esterni e cerca il codice esatto nella mappa pubblicata. Un codice sconosciuto produce un errore'
  };
  function readerText(reader, params = {}, valueMap) {
    if (!reader) return '—';
    const map = reader === 'VALUE_MAP_V1' && valueMap
      ? ['Mappa di valori ' + valueMap.name, 'Vocabolario di destinazione ' + valueMap.targetVocabulary,
         valueMap.entryCount + (valueMap.entryCount === 1 ? ' voce' : ' voci')].join(' · ')
      : params?.valueMapRef;
    return [readerRules[reader] || reader, params?.crs, map].filter(Boolean).join(' · ');
  }
  function expressionText(node, definitions) {
    if (node.items) return 'Elenco in ordine · ' + node.items.map(item => expressionText(item,definitions)).join(' · ');
    if (node.structured) return expressionText(node.structured,definitions);
    if (Object.hasOwn(node,'constructor')) return expressionText(node.constructor,definitions);
    if (node.fieldName) return node.fieldName + ' · ' + readerText(node.reader,node.transformParams);
    if (node.constructorId) {
      const rule = {
        POINT_FROM_LAT_LON_V1:'Crea un punto EPSG:4326 in ordine longitudine, latitudine. Occorrono entrambe le coordinate, entro ±180° e ±90° rispettivamente. Se entrambe mancano il punto è assente, se ne manca una sola la riga presenta un errore.',
        TEXT_JOIN_V1:'Unisce in ordine le parti presenti con il separatore ' + JSON.stringify(node.params?.separator) + '. Se mancano tutte, il testo è assente.',
        LIST_SPLIT_V1:'Divide il testo sul separatore ' + JSON.stringify(node.params?.separator) + ', legge ciascun elemento e scarta quelli assenti. Se non ne resta alcuno, l’elenco è assente. ' + readerText(node.params?.itemReader,node.params?.itemReaderParams)
      }[node.constructorId];
      return rule + ' ' + node.inputs.map(i => i.role + ' = ' + expressionText(i,definitions)).join(' · ');
    }
    const label = definitions[node.typeRef]?.label?.it || node.typeRef;
    return label + ' · ' + node.components.map(c => c.component + ' ← ' +
      expressionText(c,definitions)).join(' · ');
  }
  function definitionText(pin,definitions) {
    return [pin.label?.it || pin.semanticId,...pin.definition.components.map(c =>
      (c.labels?.it || c.name) + ' (' + c.name + ') · ' + (typeNames[c.type] || definitions[c.type]?.label?.it || c.type) +
      ' · ' + (c.cardinality === 'MANY' ? 'più valori' : 'un valore') + ' · ' + (c.required ? 'obbligatorio' : 'facoltativo') +
      (c.constraints ? ' · vincoli ' + JSON.stringify(c.constraints) : ''))].join(' — ');
  }
  function compositionsView(mapping,bindings,article) {
    if (!mapping?.compositions?.length) return;
    const definitions = Object.fromEntries((bindings || []).filter(b => b.definitionSha256 && !b.valueMapRef).map(b => [b.semanticId+'@'+b.publicationSetId,b]));
    element('h3','Proprietà composte',article);
    for (const c of mapping.compositions) {
      element('h4',c.targetPropertyIri + ' · ' + accessNames[c.dataAccessLabel],article);
      element('p',expressionText(c,definitions),article);
    }
    for (const [ref,pin] of Object.entries(definitions)) {
      element('h4','Tipo strutturato · ' + pin.label.it,article);
      element('p',definitionText(pin,definitions),article);
      element('p',ref + ' · ' + pin.definitionSha256,article);
    }
  }
  const unverified = 'etichetta non verificata dal registro';
  function termFor(card, iri) {
    return card.verifiedTerms && Object.hasOwn(card.verifiedTerms,iri) ? card.verifiedTerms[iri] : null;
  }
  function termLabel(card, iri) {
    return termFor(card,iri)?.labelIt || unverified;
  }
  function identityText(target, value, label) {
    if (target === 'sourceKey') return 'Chiave della fonte · ' + (value || []).map(field => field === '$managedRowOrdinal' ? 'posizione della riga nel file' : field).join(', ');
    if (target === 'maxCandidates') return 'Numero massimo di candidati da confrontare · ' + (value ?? unavailable);
    if (target === 'allowAutoNew') return 'Creazione automatica di nuovi oggetti · ' + (value == null ? unavailable : value ? 'Sì. In assenza di corrispondenze si può creare un nuovo oggetto.' : 'No. La creazione richiede una decisione ulteriore.');
    if (target.startsWith('sufficientRules:')) return 'Due righe descrivono lo stesso oggetto se coincidono tutti i segnali della regola «' + value.id + '» · ' + value.signalIds.map(label).join(' e ');
    if (target.startsWith('distinctRules:')) return 'Regola di distinzione «' + value.id + '» · ' + value.signalIds.map(label).join(' e ');
    if (target.startsWith('signal:')) return 'Segnale di confronto · ' + label(value.id) + ' · ' + ({TEXT_V1:'confronto testuale',DECIMAL_V1:'confronto numerico',CONCEPT:'confronto del concetto',JSON_V1:'confronto del valore strutturato'}[value.comparator] || value.comparator);
    if (target === 'exactKey') return 'Due righe descrivono lo stesso oggetto se hanno lo stesso valore per ' + label(value) + '.';
    if (target.startsWith('weighted:')) return 'Segnale di confronto · ' + label(value.property) + ' · peso ' + value.weight + ' · criterio ' + value.comparator +
      (value.tolerance == null ? '' : ' · tolleranza ' + value.tolerance);
    const criteria = {highThreshold:'Soglia di corrispondenza alta',reviewThreshold:'Soglia di revisione',minimumMargin:'Margine minimo fra candidati',
      blockingProperties:'Proprietà usate per selezionare i candidati',blockingDistanceMeters:'Distanza massima in metri',allowSpatialIdentity:'Confronto spaziale per l’identità'};
    if (Object.hasOwn(criteria,target)) return criteria[target] + ' · ' + (Array.isArray(value) ? value.map(label).join(', ') : typeof value === 'boolean' ? (value ? 'sì' : 'no') : value ?? unavailable);
    if (target === 'strategy') return 'Strategia di confronto · ' + ({GOVERNED_IDENTITY:'regole di identità governate',EXACT_SOURCE_KEY:'uguaglianza della chiave della fonte'}[value] || value);
    return 'Criteri di confronto · ' + JSON.stringify(value);
  }
  function fieldDescription(row) {
    return [row.included ? 'inclusa' : 'esclusa', row.property, row.transform && readerText(row.transform,row.transformParams,row.valueMap), row.access, row.sourceKey && 'chiave della fonte', row.signal && 'segnale d’identità',row.authorityOrder?.length && 'ordine delle autorità ' + row.authorityOrder.join(', '), row.reconciliation && 'regole ' + JSON.stringify(row.reconciliation)].filter(Boolean).join(' · ');
  }
  function semanticReference(card, iri, parent, warnings) {
    sourceLink(parent,iri,iri);
    const term = termFor(card,iri);
    if (!term) {
      warnings.push(iri + ' · ' + unverified);
      return;
    }
    element('p',[term.labelIt, 'verificato dal registro semantico', term.artifactType === 'CLASS' ? 'Classe' : 'Proprietà',
      term.descriptionIt || 'descrizione italiana assente', 'Editore: ' + (term.publisher || unavailable),
      'Licenza: ' + (term.license || unavailable), 'versione ' + term.semanticVersion, 'revisione ' + term.revisionId,
      'set ' + term.publicationSetId].join(' · '),parent);
    if (!term.license || term.licenseWarning) warnings.push(term.labelIt + ' · ' + (term.licenseWarning || 'licenza non indicata nel registro.'));
    if (!term.descriptionIt) warnings.push(term.labelIt + ' · descrizione italiana assente nel registro.');
  }
  function showWarnings(warnings, article) {
    if (!warnings.length) return;
    const section = element('section','',article); section.className = 'warnings'; element('h3','Avvisi da esaminare',section);
    const list = element('ul','',section); for (const warning of warnings) element('li',warning,list);
  }
  function pinnedReference(ref) {
    if (!ref) return ref;
    const {kind,semanticId,semanticVersion,revisionId,publicationSetId} = ref;
    return {kind,semanticId,semanticVersion,revisionId,publicationSetId};
  }
  function reviewCard(card, article, flags) {
    const snap = card.reviewSnapshot, file = snap.file, warnings = [];
    const editable = canApprove({card});
    const label = iri => termFor(card,iri)?.labelIt || iri;
    function flag(section, target, parent) {
      if (!editable) return;
      const box = document.createElement('input'); box.type = 'checkbox';
      const choice = element('label','',parent); choice.append(box); element('span','Da modificare',choice);
      box.setAttribute('aria-label','Da modificare · ' + sectionNames[section] + ' ' + target);
      const comment = element('textarea','',parent); comment.maxLength = 500; comment.rows = 2;
      comment.placeholder = 'Commento facoltativo (500 caratteri)';
      comment.setAttribute('aria-label','Commento per ' + sectionNames[section] + ' ' + target);
      comment.hidden = true;
      box.onchange = () => { comment.hidden = !box.checked; controls(); };
      flags.push({section,target,box,comment});
    }
    const heading = editable && card.termVerification === 'COMPLETE' ? 'Stai approvando l’attivazione della fonte' : 'Richiesta di attivazione della fonte';
    element('h2', heading + ' «' + snap.sourceName + '» (' + card.source_id + ') del proprietario ' + snap.owner,article);
    const facts = element('dl','',article);
    const fact = (name,value) => { element('dt',name,facts); return element('dd',value,facts); };
    fact('File',file.name || 'Nome originale non disponibile · ' + file.assetId);
    fact('Dimensione',file.size + ' byte'); fact('Profilo del file',(file.rows ?? unavailable) + ' righe · ' + file.columns + ' colonne · ' + file.format);
    for (const target of card.configuration.semanticMapping.targetClasses) {
      const dd = fact('Classe di destinazione',termLabel(card,target.classIri) + ' · ');
      sourceLink(dd,target.classIri,target.classIri);
      if (target.category) fact('Categoria',target.category.conceptIri + ' · vocabolario ' + target.category.schemeIri);
    }
    fact('Versione dell’onboarding',card.version);
    fact('Scadenza della richiesta',new Date(card.expires_at).toLocaleString('it-IT',{timeZoneName:'short'}));
    if (card.previousReview) {
      const previous = card.previousReview, diff = element('section','',article); diff.className = 'warnings';
      element('h3','Differenze rispetto alla versione ' + previous.version,diff);
      const describe = (section,target,value) => value == null ? 'assente' : section === 'FIELD' ? fieldDescription(value) :
        section === 'COMPOSITION' ? expressionText(value,snap.DATATYPE || {}) + ' · ' + value.dataAccessLabel :
        section === 'DATATYPE' ? definitionText(value,snap.DATATYPE || {}) : section === 'IDENTITY' ? identityText(target,value,label) : section === 'SEMANTIC' ? JSON.stringify(value) : String(value);
      const changes = previous.changes.map(change => change.section === 'SEMANTIC'
        ? {...change,before:pinnedReference(change.before),after:pinnedReference(change.after)} : change)
        .filter(change => change.section !== 'SEMANTIC' || JSON.stringify(change.before) !== JSON.stringify(change.after));
      for (const change of changes) {
        const {section,target,before,after} = change;
        element('p',sectionNames[section] + ' ' + (limitNames[target] || target) + ' · ' + describe(section,target,before) + ' → ' + describe(section,target,after),diff);
      }
      if (changes.length) detail('Valori precedenti e proposti per ogni differenza',changes,diff);
      if (!changes.length) element('p','Nessuna differenza nelle voci esaminate.',diff);
      for (const note of previous.feedback) {
        const changed = changes.some(change => change.section === note.section && change.target === note.target);
        element('p','Riscontro precedente · ' + sectionNames[note.section] + ' ' + (limitNames[note.target] || note.target) + ' · ' +
          (note.comment || 'segnalato senza commento') + (changed ? ' (voce modificata)' : ' (voce invariata)'),diff);
      }
    }
    element('h3','Campi del file',article);
    const wrap = element('div','',article); wrap.className = 'field-scroll'; wrap.tabIndex = 0;
    wrap.setAttribute('role','region'); wrap.setAttribute('aria-label','Campi del file, tabella scorrevole');
    const table = element('table','',wrap); element('caption','Decisione di acquisizione per ogni colonna del file',table);
    const head = element('tr','',element('thead','',table));
    for (const text of ['Colonna del file','Decisione','Proprietà semantica','Tipo di dato','Accesso','Ruolo',...(editable?['Riscontro']:[])]) element('th',text,head).scope = 'col';
    const rows = element('tbody','',table);
    for (const name of snap.columnOrder) {
      const row = snap.FIELD[name], tr = element('tr','',rows);
      if (!row.included) tr.className = 'excluded';
      element('th',name,tr).scope = 'row';
      element('td',row.included ? 'Inclusa' : 'Esclusa · non verrà acquisita',tr);
      const property = element('td',row.property ? termLabel(card,row.property) + ' · ' : '—',tr);
      if (row.property) sourceLink(property,row.property,row.property);
      if (row.reconciliation) detail('Regole di riconciliazione della proprietà',row.reconciliation,property);
      if (row.authorityOrder?.length) element('p','Ordine delle autorità · ' + row.authorityOrder.join(', '),property);
      element('td',readerText(row.transform,row.transformParams,row.valueMap),tr);
      element('td',row.access ? row.access + ' · ' + accessNames[row.access] : 'Non indicato',tr);
      element('td',[row.sourceKey && 'chiave della fonte',row.signal && 'segnale d’identità'].filter(Boolean).join(' · ') || '—',tr);
      if (editable) flag('FIELD',name,element('td','',tr));
      if (!row.access) warnings.push(name + ' · classificazione di accesso non disponibile nello snapshot.');
      if (['PERSONAL','SENSITIVE','RESTRICTED'].includes(row.access)) warnings.push(name + ' · ' + accessNames[row.access] + (row.included ? ' · inclusa' : ' · esclusa'));
      if (row.included && row.transform === 'IDENTITY' && ['DECIMAL','LONG'].includes(row.profileType)) warnings.push(name + ' · il profilo rileva un numero che verrà acquisito come testo.');
    }
    for (const [section,title] of [['IDENTITY','Identità degli oggetti'],['SEMANTIC','Riferimenti semantici'],['LIMITS','Limiti dell’acquisizione']]) {
      element('h3',title,article);
      if (section === 'SEMANTIC' && snap.bindings?.length) {
        detail('Versioni e insiemi di pubblicazione vincolati alla configurazione',snap.bindings.map(pinnedReference),article);
      }
      const list = element('ul','',article); list.className = 'review-items';
      for (const [target,value] of Object.entries(snap[section])) {
        const item = element('li','',list);
        if (section === 'IDENTITY') element('span',identityText(target,value,label),item);
        if (section === 'LIMITS') element('span',limitNames[target] + ' · ' + (value ?? unavailable),item);
        if (section === 'SEMANTIC') {
          semanticReference(card,target,item,warnings);
        }
        flag(section,target,item);
      }
    }
    showWarnings(warnings,article);
    element('h3','Cosa comporta l’approvazione',article);
    element('p','Approvi questa configurazione. Con il successivo comando «Verifica e attiva», superate le verifiche, la fonte diventa attiva. Ingestion acquisisce il file e UDP materializza gli oggetti con le sole proprietà incluse e le regole d’identità mostrate. Le colonne escluse non vengono acquisite. Puoi rivedere la configurazione o disattivare la fonte per fermare le acquisizioni future.',article);
  }
  const selected = () => [...cards.values()].filter(item => !semantic || item.checkbox.checked);
  const expired = card => Date.parse(card.expires_at) <= Date.now();
  const canApprove = item => semantic ? item.card.status === 'OPEN' && !expired(item.card) : item.card.status === 'CREATED' && item.card.onboardingState === 'IN_REVIEW' && !expired(item.card);
  const missingFileSnapshot = card => !card.reviewSnapshot && (card.configuration?.extractionProfile?.selection?.assetId || card.configuration?.semanticMapping?.sourceType?.typeCode === 'FILE');
  const approvalBlocked = card => semantic ? card.artifact_type === 'VALUE_MAP' && !hasValueMapReview(card)
    : missingFileSnapshot(card) || card.termVerification !== 'COMPLETE';
  const canComplete = item => semantic ? item.card.status === 'APPROVED' && item.card.lifecycleStatus !== 'ACTIVE' && !!item.card.approvalDecisionId : !item.needsRefresh && item.card.status === 'CONFIRMED' && item.card.onboardingState === 'APPROVED' && !item.card.progress?.activationProgress?.budgetExhausted && item.card.progress?.activationProgress?.counterLineageVerified !== false && !['BLOCKED','EXHAUSTED'].includes(item.card.progress?.activationProgress?.readiness_phase) && !(Date.parse(item.card.progress?.activationProgress?.readiness_lease_until) > Date.now()) && !(Date.parse(item.card.progress?.activationProgress?.readiness_next_at) > Date.now());
  const canBudget = item => !semantic && !item.needsRefresh && item.card.onboardingState === 'APPROVED' && item.card.status === 'CONFIRMED' && item.card.progress?.activationProgress?.humanBudgetGrantAvailable === true && (item.reason?.value.trim().length || 0) >= 10;
  const canResume = item => !semantic && !item.needsRefresh && item.card.progress?.activationProgress?.humanRecheckAllowed === true;
  function blockedReadiness(card, progress) {
    const guidance = document.createElement('section'); guidance.className = 'warnings';
    const code = progress.readiness_last_code;
    const http = /^ONB_READINESS_DEPENDENCY_([0-9]{3})$/.exec(code)?.[1];
    let failure = 'L’ultima verifica per l’attivazione si è bloccata.';
    if (http) failure = card.progress.ingestionCompatibilityObserved === false
      ? `La verifica di compatibilità con Ingestion ha ricevuto un rifiuto (HTTP ${http}).`
      : `Una dipendenza ha rifiutato la verifica di compatibilità con Ingestion o il controllo di identità con UDP (HTTP ${http}).`;
    if (code === 'ONB_READINESS_COMPATIBILITY_UNPROVEN') failure = 'La verifica di compatibilità con Ingestion non ha dato una conferma valida.';
    if (['ONB_READINESS_IDENTITY_UNPROVEN', 'ONB_UDP_IDENTITY_PREFLIGHT_REQUIRED', 'ONB_UDP_IDENTITY_EVIDENCE_UNPROVEN'].includes(code))
      failure = 'Il controllo di identità con UDP non ha dato una conferma valida.';
    element('h3', 'Verifica di attivazione bloccata', guidance);
    element('p', failure, guidance);
    if (progress.humanRecheckAllowed === true) {
      const remaining = progress.maximumAttempts - progress.sessionAttempts;
      const deadline = new Date(progress.budgetExpiresAt).toLocaleString('it-IT', {timeZoneName:'short'});
      element('p', `“Riprendi” registra la richiesta di un nuovo controllo completo. Il budget attuale conserva ${remaining} ${remaining === 1 ? 'tentativo' : 'tentativi'} fino al ${deadline}.`, guidance);
      element('p', 'Dopo la ripresa scegli “Verifica e attiva”. OUF verifica la compatibilità con Ingestion e l’identità con UDP. Se tutti i controlli passano, la configurazione viene attivata e può partire l’acquisizione.', guidance);
    } else {
      element('p', progress.humanBudgetGrantAvailable === true
        ? 'Il ricontrollo richiede un nuovo budget. In alternativa, torna nella chat e chiedi all’agente di preparare una nuova versione della fonte.'
        : 'Il ricontrollo di questa versione non è disponibile. Torna nella chat e chiedi all’agente di preparare una nuova versione della fonte.', guidance);
    }
    return guidance;
  }
  function controls() {
    const items = selected(); $('actions').disabled = busy || items.length === 0;
    const flagged = items.some(item => item.flags.some(flag => flag.box.checked));
    $('approve').disabled = flagged || !items.length || !items.every(canApprove) || items.some(item => approvalBlocked(item.card));
    $('changes').disabled = !flagged || !items.every(canApprove);
    $('reject').disabled = flagged || !items.length || !items.every(canApprove);
    $('complete').disabled = !items.length || !items.every(canComplete);
    $('resume').disabled = !items.length || !items.every(canResume);
    $('budget').disabled = !items.length || !items.every(canBudget);
  }
  function render(id, response) {
    const card = response.card;
    let reason;
    const flags = [];
    if (card.challenge_id !== id) throw new Error('La risposta non appartiene alla revisione richiesta.');
    const article = document.createElement('article'); article.className = 'card';
    if (semantic) semanticSummary(card, article);
    else if (card.reviewSnapshot) reviewCard(card,article,flags);
    else {
      element('h2', card.source_name || card.source_id, article);
      element('p', `Fonte dati · ${card.source_id}`, article);
      if (missingFileSnapshot(card)) element('p','La tabella completa del proprietario non è disponibile. Aggiorna la scheda prima di approvare.',article).className = 'warnings';
    }
    const states = {IN_REVIEW:'in revisione', APPROVED:'approvata', ACTIVE:'attiva', REJECTED:'rifiutata', CHANGES_REQUESTED:'modifiche richieste', SUPERSEDED:'sostituita', REVOKED:'revocata'};
    const state = semantic ? card.lifecycleStatus || card.status : card.reviewSnapshot ? states[card.onboardingState] || card.onboardingState : card.onboardingState;
    element('p','Stato: ' + state,article).className = 'state';
    if (card.progress?.reviewFeedback?.length) {
      element('h3','Modifiche richieste all’agente',article);
      for (const note of card.progress.reviewFeedback) element('p',sectionNames[note.section] + ' ' + note.target + ' · ' + (note.comment || 'segnalato senza commento'),article);
    }
    if (expired(card) && ["CREATED", "OPEN"].includes(card.status)) element('p', 'La richiesta è scaduta. Torna alla chat per la riconciliazione.', article);
    if (semantic) {
      const definition = card.definition_json || {};
      detail('Definizione, ambito e vincoli', definition, article);
      if (card.interchangeSnapshot) detail('Contenuto semantico esatto', card.interchangeSnapshot, article);
    } else {
      const config = card.configuration || {}, mapping = config.semanticMapping || {};
      if (card.termVerification !== 'COMPLETE') {
        const reason = card.termVerification === 'UNAVAILABLE' ? 'Registro semantico non disponibile.' : 'La verifica dei termini è incompleta.';
        const guidance = canApprove({card}) ? ' Per approvare, aggiorna la scheda quando le etichette sono verificabili.' : '';
        element('p',reason + guidance,article).className = 'warnings';
      }
      if (!card.reviewSnapshot) {
      element('h3', 'Classi di destinazione', article);
      for (const target of mapping.targetClasses || []) {
        element('h3', termLabel(card,target.classIri) + ' · ' + target.classIri, article);
        if (target.category) {
          element('p', `Vocabolario: ${target.category.schemeIri}`, article);
          element('p', `Categoria: ${target.category.conceptIri}`, article);
        } else element('p', 'Categoria assente', article);
      }
      const rows = mapping.propertyMappings || [];
      const labels = new Map((config.dataAccessPolicies || []).filter(p => p.scope === 'PROPERTY').map(p => [p.target, p.label]));
      element('h3', 'Campi inclusi e corrispondenze', article);
      const table = document.createElement('table'), head = document.createElement('tr');
      for (const name of ['Campo della fonte', 'Proprietà', 'Trasformazione', 'Etichetta di accesso']) element('th', name, head);
      table.append(head);
      const appendField = (field, property, transform) => {
        const tr = document.createElement('tr');
        for (const value of [field, property && termLabel(card,property) + ' · ' + property, transform, labels.get(property) || labels.get(field)]) element('td', value, tr);
        table.append(tr);
      };
      for (const row of rows) appendField(row.sourceField, row.targetPropertyIri, readerText(row.transform,row.transformParams,row.valueMap));
      const mapped = new Set(rows.map(row => row.sourceField));
      const projection = config.extractionProfile?.projection?.[mapping.sourceType?.typeCode || 'FILE'] || [];
      for (const field of projection) if (!mapped.has(field)) appendField(field, null, null);
      article.append(table);
      const iris = new Set([...(mapping.targetClasses || []).map(row => row.classIri), ...rows.map(row => row.targetPropertyIri)]);
      if (iris.size) {
        element('h3','Riferimenti semantici',article);
        const list = element('ul','',article), warnings = [];
        list.className = 'review-items';
        for (const iri of iris) semanticReference(card,iri,element('li','',list),warnings);
        showWarnings(warnings,article);
      }
      element('h3', 'Campi esclusi', article);
      const excluded = (card.newColumns || []).filter(item => item.decision === 'EXCLUDE');
      if (!excluded.length) element('p', 'La scheda della fonte non elenca campi esclusi.', article);
      for (const item of excluded) element('p', `${item.column} · Nessuna proprietà pubblicata · Accesso: ${item.accessLabel || 'non indicato'}`, article);
      }
      if (mapping.compositions?.length || mapping.propertyMappings?.some(p => p.transform && p.transform !== 'IDENTITY'))
        element('p','Con i lettori diversi da IDENTITY, una cella vuota o di soli spazi dà un valore assente. Un componente obbligatorio deve avere un valore.',article);
      compositionsView(mapping,config.semanticReferenceBindings,article);
      if (card.newColumns?.length) {
        element('h3', 'Decisioni sulle colonne nuove', article);
        const decisions = document.createElement('table'), header = document.createElement('tr');
        for (const label of ['Colonna nuova', 'Decisione', 'Effetto sulla proiezione']) element('th', label, header);
        decisions.append(header);
        for (const item of card.newColumns) {
          const row = document.createElement('tr');
          element('td', item.column, row);
          element('td', item.decision, row);
          const effect = element('td', item.decision === 'EXCLUDE' ? 'Conservata solo nel file' :
            termLabel(card,item.mapping.propertyIri) + ' · ' + item.mapping.propertyIri, row);
          if (item.decision === 'MAP') detail('Trasformazione e regole', item.mapping, effect);
          decisions.append(row);
        }
        article.append(decisions);
      }
      detail('Identità delle righe e classificazione di accesso', { sourceObjectIdentityPolicy: config.sourceObjectIdentityPolicy, dataAccessPolicies: config.dataAccessPolicies }, article);
      detail('Confronto con gli oggetti esistenti e materializzazione', config.extractionProfile?.runtime?.udp, article);
      if (card.publicationEffects) {
        element('h3', 'Effetto della nuova pubblicazione', article);
        element('p', card.publicationEffects.description, article);
        detail('Versione di partenza della revisione', card.revisionBase, article);
        if (card.assetRef) detail('Nuovo file e profilo da approvare', card.assetRef, article);
      }
      const {semanticReviewTerms, ...configuration} = config;
      if (Array.isArray(configuration.semanticReferenceBindings))
        configuration.semanticReferenceBindings = configuration.semanticReferenceBindings.map(pinnedReference);
      detail('Configurazione da approvare', configuration, article);
      const activation = card.progress?.activationProgress;
      if (activation?.readiness_phase === 'BLOCKED' && card.onboardingState === 'APPROVED')
        article.querySelector('h2').after(blockedReadiness(card, activation));
      if (activation) detail('Avanzamento delle verifiche e tentativi disponibili', activation, article);
      if(card.progress?.activationProgress?.budgetExhausted && card.onboardingState === 'APPROVED') {
        element('p', 'Il tempo o i tentativi disponibili per le verifiche sono terminati. La configurazione resta approvata.', article);
        if(card.progress.activationProgress.humanBudgetGrantAvailable === true) {
          element('p', `Puoi concedere fino a 3 nuovi tentativi entro 15 minuti. I ${card.progress.activationProgress.cumulativeAttempts} tentativi già registrati e lo storico restano conservati. Poi potrai scegliere separatamente “Verifica e attiva”, che può pubblicare la configurazione e avviare un’acquisizione.`, article);
          const label = document.createElement('label'); element('span', 'Motivo della nuova concessione (10–500 caratteri, senza credenziali)', label);
          reason = document.createElement('textarea'); reason.maxLength = 500; reason.rows = 3;
          if (card.progress.activationProgress.reconciledBudgetReason)
            reason.value = 'Ripetere le verifiche della dipendenza entro i limiti indicati.';
          if (card.progress.activationProgress.reconciledBudgetReason === 'ONB_READINESS_INTERRUPTED')
            reason.value = 'Ripetere le verifiche dopo il tentativo interrotto senza pubblicazione.';
          if (card.progress.activationProgress.reconciledBudgetReason === 'ONB_UDP_IDENTITY_PREFLIGHT_REQUIRED')
            reason.value = 'Richiedere una nuova prova UDP per la configurazione approvata.';
          if (card.progress.activationProgress.reconciledBudgetReason === 'ONB_READINESS_DEPENDENCY_403')
            reason.value = 'Verificare di nuovo le autorizzazioni correnti della dipendenza.';
          reason.oninput = controls; label.append(reason); article.append(label);
        } else if (activation.readiness_phase !== 'BLOCKED') element('p', 'Occorre riconciliare lo stato prima di concedere altri tentativi.', article);
      }
      if (canComplete({card}) && !card.progress?.ingestionCompatibilityObserved) element('p', 'Approvazione registrata. “Verifica e attiva” controlla la configurazione con Ingestion e confronta l’identità con gli oggetti esistenti prima di attivare.', article);
    }
    detail('Riferimenti della revisione', { challenge: id, version: card.onboarding_version_id || card.revision_id, hash: card.configuration_hash || card.target_content_hash, expiresAt: card.expires_at }, article);
    const label = document.createElement('label'), checkbox = document.createElement('input'); checkbox.type = 'checkbox';
    if (semantic) {
      label.append(checkbox); element('span', 'Ho esaminato questa proposta e la seleziono per la decisione.', label); article.append(label);
    }
    const previous = cards.get(id); if (previous) { previous.article.replaceWith(article); } else $('cards').append(article);
    const intent = document.createElement('output'); intent.hidden = true; article.append(intent);
    intent.oninput = () => { intent.hidden = !intent.value; };
    cards.set(id, { card, csrfHeader: response.csrfHeader, csrfToken: response.csrfToken, checkbox, article, reason, intent, flags });
    checkbox.onchange = controls;
    const fields = [intent, ...flags.flatMap(flag => [flag.box,flag.comment])];
    if (semantic) fields.push(checkbox);
    if (reason) fields.push(reason);
    ThsSession.bind(JSON.stringify([id, card.target_content_hash || card.configuration_hash, card.status, card.lifecycleStatus,
      card.lockVersion, card.progress?.activationProgress?.readiness_generation]), fields);
  }
  async function load(id) {
    const response = await ThsSession.fetch(`api/${encodeURIComponent(id)}`, { credentials: 'same-origin', cache: 'no-store', headers: { Accept: 'application/json' } });
    if (!response.ok) throw new Error(`Revisione non disponibile (HTTP ${response.status}).`);
    render(id, await response.json());
  }
  async function refresh() {
    if (busy) return; busy = true; controls();
    try { for (const id of ids) await load(id); status('Esamina le proposte. Approvazione e pubblicazione o attivazione sono decisioni distinte.'); }
    catch (error) { status(error.message, true); }
    finally { busy = false; controls(); }
  }
  async function decide(action) {
    const items = selected(); if (busy || !items.length) return;
    if (action === 'resume' ? !items.every(canResume) : action === 'budget' ? !items.every(canBudget) : action === 'complete' ? !items.every(canComplete) : !items.every(canApprove)) return;
    const feedback = items.flatMap(item => item.flags.filter(flag => flag.box.checked)
      .map(flag => ({section:flag.section,target:flag.target,comment:flag.comment.value.trim()})));
    if (['approve','reject'].includes(action) && feedback.length) return;
    if (action === 'approve' && items.some(item => approvalBlocked(item.card))) return;
    if (action === 'changes' && (!feedback.length || feedback.length > 128 || new Blob([JSON.stringify(feedback)]).size > 48*1024)) {
      status('Seleziona da 1 a 128 voci e mantieni il riscontro entro 48 KiB.',true); return;
    }
    busy = true; controls(); let completed = 0;
    for (const item of items) item.intent.value = `Scelta da verificare dopo il nuovo accesso: «${$(action).textContent}».`;
    try {
      for (const item of items) {
        const card = item.card, id = card.challenge_id;
        const operation = semantic ? (action === 'complete' ? 'publish' : 'decision') : (action === 'resume' ? 'resume' : action === 'budget' ? 'budget' : action === 'complete' ? 'activate' : action === 'changes' ? 'changes' : action === 'approve' ? 'confirm' : 'reject');
        let body = semantic ? (operation === 'publish' ? { approvalDecisionId: card.approvalDecisionId, contentHash: card.target_content_hash } : { decision: action === 'approve' ? 'APPROVED' : 'REJECTED', expectedContentHash: card.target_content_hash }) : { configurationHash: card.configuration_hash, expectedVersion: card.lockVersion };
        if (action === 'changes') body.reviewFeedback = feedback;
        if (action === 'budget') {
          const progress = card.progress.activationProgress;
          const slot = `ouf-readiness-budget:${id}:${card.configuration_hash}:${progress.readiness_generation}`;
          let key = sessionStorage.getItem(slot);
          if (!key) { key = crypto.randomUUID(); sessionStorage.setItem(slot, key); }
          body = { configurationHash: card.configuration_hash, expectedVersion: card.lockVersion,
            expectedGeneration: progress.readiness_generation, commandKey: key, reason: item.reason.value.trim(),
            maximumAttempts: 3, maximumElapsedSeconds: 900 };
        }
        if (operation === 'resume') Object.assign(body, { expectedGeneration: card.progress.activationProgress.readiness_generation, blockedCode: card.progress.activationProgress.readiness_last_code });
        const readinessAction = !semantic && ['activate','resume','budget'].includes(operation);
        if (readinessAction) item.needsRefresh = true;
        const response = await ThsSession.fetch(`api/${encodeURIComponent(id)}/${operation}`, { method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json', [item.csrfHeader]: item.csrfToken }, body: JSON.stringify(body) });
        if (!response.ok) {
          const failure = await response.json().catch(() => null);
          const code = failure?.code || failure?.detail;
          const reason = typeof code === 'string' && /^[A-Z][A-Z0-9_]{1,159}$/.test(code) ? ' · ' + code : '';
          if (readinessAction) await load(id).catch(() => {});
          throw new Error(`Operazione non completata (HTTP ${response.status}${reason}).`);
        }
        await load(id); completed++;
      }
      status(action === 'resume' ? 'Ripresa registrata. Per proseguire scegli “Verifica e attiva”.' : `${completed} decisioni registrate. Puoi tornare alla chat per proseguire.`);
    } catch (error) {
      status(`${error.message} Decisioni già confermate in questa operazione: ${completed}. Aggiorna lo stato; nessun invio viene ripetuto automaticamente.`, true);
    } finally { busy = false; controls(); }
  }
  $('title').textContent = semantic ? 'Rivedi e pubblica la semantica' : 'Rivedi e attiva la fonte';
  $('approve').textContent = semantic ? 'Approva le proposte selezionate' : 'Approva la configurazione';
  $('complete').textContent = semantic ? 'Pubblica le proposte approvate' : 'Verifica e attiva la fonte approvata';
  $('resume').hidden = semantic; $('resume').onclick = () => decide('resume');
  $('changes').hidden = semantic; $('changes').onclick = () => decide('changes');
  $('budget').hidden = semantic;
  $('budget').onclick = () => decide('budget');
  $('approve').onclick = () => decide('approve'); $('complete').onclick = () => decide('complete'); $('reject').onclick = () => decide('reject'); $('refresh').onclick = refresh;
  if (!semantic && !params.has('challenge') && !params.has('challenges')) {
    $('title').textContent = 'Accesso effettuato';
    status('Accesso effettuato: apri il link della richiesta da approvare');
    for (const id of ['intro','refresh','actions']) $(id).hidden = true;
    document.querySelector('.note').hidden = true;
  } else if (!['onboarding', 'semantic'].includes(flow) || ids.length > (semantic ? 21 : 1) || new Set(ids).size !== ids.length || !ids.every(id => uuid.test(id))) status('Apri il collegamento di revisione fornito dalla chat.', true);
  else refresh();
})();

