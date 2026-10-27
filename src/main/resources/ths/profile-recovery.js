'use strict';
(()=>{
  const $=id=>document.getElementById(id),params=new URLSearchParams(location.search),asset=params.get('asset'),job=params.get('job');
  const uuid=/^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/;
  if(!uuid.test(asset||'')||!uuid.test(job||'')){$('message').textContent='Il collegamento al lavoro è incompleto. Recuperalo dalla conversazione OUF.';$('refresh').disabled=true;return;}
  const endpoint=`api/${asset}/${job}`,storage=`ouf-profile-recovery.v1:${asset}:${job}`;
  let card,csrfHeader,csrfToken,busy=false,pending=null,monitor=null;
  try{pending=JSON.parse(sessionStorage.getItem(storage)||'null');if(pending&&(!['budget','resume','cancel'].includes(pending.action)||!pending.body||typeof pending.body!=='object'))pending=null;}catch(_){}
  const message=text=>{$('message').textContent=text;};
  function controls(){const confirmed=$('confirmed').checked,reason=$('reason').value.trim();
    $('budget').disabled=busy||!!pending||!confirmed||reason.length<10||!card?.budgetAvailableToGrant;
    $('resume').disabled=busy||!!pending||!confirmed||!card?.canResume;
    $('cancel').disabled=busy||!!pending||!confirmed||reason.length<10||!card?.canCancel;
    $('refresh').disabled=busy;$('reconcile').hidden=!pending;$('reconcile').disabled=busy||!csrfToken;
  }
  function render(){
    $('review').hidden=false;$('facts').replaceChildren();
    const state={FAILED:'Analisi ferma',READY:'Analisi in coda',RUNNING:'Analisi in corso',RETRY_WAIT:'In pausa per un errore transitorio',SUCCEEDED:'Analisi completata',CANCELLED:'Recupero annullato'};
    const facts=[['Stato',state[card.status]||'Da verificare'],['Caricato il',card.uploadedAt],['Dimensione',`${card.sizeBytes} byte`],['Tentativi cumulativi',card.cumulativeAttempts],['Ultimo blocco',card.lastFailureCategory==='OBJECT_READ_FORBIDDEN'?'Lettura del file non autorizzata':card.lastFailureCategory||'Non disponibile']];
    if(card.sessionId)facts.push(['Sessione',card.sessionState],['Tentativi della sessione',`${card.sessionUsedAttempts} di 3`],['Scadenza',new Date(card.sessionExpiresAt).toLocaleString('it-IT')]);
    for(const [label,value]of facts){const dt=document.createElement('dt'),dd=document.createElement('dd');dt.textContent=label;dd.textContent=String(value);$('facts').append(dt,dd);}
    $('identity').textContent=`File: ${asset}\nJob: ${job}\nContenuto: ${card.contentHash}\n${card.sessionId?'Sessione: '+card.sessionId+'\nDecisione: '+card.decisionRef:''}`;
    $('history').replaceChildren();for(const event of card.history||[]){const li=document.createElement('li');li.textContent=`${event.created_at} · ${event.event_type} · tentativi ${event.cumulative_attempts}`;$('history').append(li);}
    $('truncated').textContent=card.historyTruncated?'Sono mostrate le ultime 16 transizioni. La storia completa è conservata.':'';
    $('confirmed').checked=false;controls();
  }
  async function request(path,options={}){const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),16000);
    try{const response=await fetch(path,{credentials:'same-origin',redirect:'error',cache:'no-store',signal:controller.signal,...options});
      if(!response.ok){const error=new Error();error.status=response.status;throw error;}return await response.json();
    }finally{clearTimeout(timer);}
  }
  async function refresh(silent=false){if(busy)return;busy=true;controls();try{const result=await request(endpoint);card=result.card;csrfHeader=result.csrfHeader;csrfToken=result.csrfToken;render();
      if(!silent)message(pending?'Esito precedente da riconciliare. Nessun invio viene ripetuto automaticamente.':card.status==='SUCCEEDED'?'Analisi completata. Il lavoro conservato è pronto per il passo successivo.':'Stato verificato. Esamina il lavoro e scegli l’azione.');
    }catch(error){monitor=null;card=null;$('review').hidden=true;csrfToken=null;message(error.status===401||error.status===403?'Accesso non autorizzato o sessione scaduta. Accedi di nuovo a OUF.':'Stato non verificato. Aggiorna prima di decidere; nessuna azione è stata inviata.');}
    finally{busy=false;controls();}}
  function storePending(value){pending=value;try{if(value)sessionStorage.setItem(storage,JSON.stringify(value));else sessionStorage.removeItem(storage);}catch(_){}controls();}
  async function send(action,body){if(busy||!csrfToken)return;monitor=null;busy=true;storePending({action,body});controls();
    try{card=await request(endpoint+'/'+action,{method:'POST',headers:{'Content-Type':'application/json',[csrfHeader]:csrfToken},body:JSON.stringify(body)});storePending(null);render();
      message(action==='budget'?'Budget concesso. L’analisi resta ferma: verifica la revisione, poi scegli Avvia il recupero.':action==='resume'?'Recupero avviato sullo stesso job. Verifico l’esito entro una finestra finita.':'Recupero annullato; storia e risultati conservati.');
      if(action==='resume'&&!['SUCCEEDED','FAILED','CANCELLED'].includes(card.status))monitor={start:Date.now(),reads:0};
    }catch(error){message(error.status===401||error.status===403?'Azione non autorizzata. Aggiorna l’accesso prima di riconciliare.':'Esito non confermato. Nessun invio viene ripetuto automaticamente: aggiorna lo stato o riconcilia la stessa richiesta.');}
    finally{busy=false;controls();if(monitor)setTimeout(poll,6000);}}
  async function poll(){if(!monitor||busy)return;if(monitor.reads>=12||Date.now()-monitor.start>=90000){monitor=null;message('Monitoraggio terminato. Il lavoro conserva il proprio stato; usa Aggiorna stato.');return;}
    monitor.reads++;await refresh(true);if(!monitor)return;
    if(['SUCCEEDED','FAILED','CANCELLED'].includes(card?.status)){monitor=null;message(card.status==='SUCCEEDED'?'Analisi completata sullo stesso job.':'Il recupero si è fermato. Esamina lo stato conservato; nessun nuovo budget è stato concesso.');return;}
    setTimeout(poll,6000);
  }
  function common(){return{expectedVersion:card.expectedVersion,expectedGeneration:card.expectedGeneration,inputBindingHash:card.inputBindingHash};}
  $('budget').addEventListener('click',()=>send('budget',{...common(),reviewHash:card.reviewHash,commandKey:crypto.randomUUID(),reason:$('reason').value.trim(),maximumAttempts:3,maximumElapsedSeconds:900}));
  $('resume').addEventListener('click',()=>send('resume',{...common(),sessionId:card.sessionId}));
  $('cancel').addEventListener('click',()=>send('cancel',{...common(),commandKey:crypto.randomUUID(),reason:$('reason').value.trim()}));
  $('reconcile').addEventListener('click',()=>{if(pending)send(pending.action,pending.body);});
  $('refresh').addEventListener('click',()=>{monitor=null;refresh();});$('confirmed').addEventListener('change',controls);$('reason').addEventListener('input',controls);
  refresh();
})();
