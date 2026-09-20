'use strict';
(() => {
 const $=id=>document.getElementById(id), id=new URLSearchParams(location.search).get('proposal');
 let card,csrfHeader,csrfToken,busy=false;
 const message=value=>{$('status').textContent=value;};
 function text(tag,value,parent){const el=document.createElement(tag);el.textContent=String(value);parent.append(el);return el;}
 function pair(dl,label,value){text('dt',label,dl);text('dd',value,dl);}
 function grant(value,parent){parent.replaceChildren();if(!value){text('p','Nessuna abilitazione con questo identificativo.',parent);return;}const dl=document.createElement('dl');parent.append(dl);pair(dl,'Capability',value.capabilityId);pair(dl,'Soggetto',value.subjectId||'—');pair(dl,'Ruolo IAM',value.constraints?.externalRoleRef||'—');pair(dl,'Effetto',value.constraints?.effect||'ALLOW');pair(dl,'Valido da',value.validFrom);pair(dl,'Valido fino a',value.validUntil);if(value.servicePrincipalId)pair(dl,'Servizio',value.servicePrincipalId);}
 const endpoint=`/trusted-human/authorization/api/proposals/${encodeURIComponent(id||'')}`;
 function controls(enabled){$('confirm').disabled=!enabled||!$('checked').checked;$('reject').disabled=!enabled;$('checked').disabled=!enabled;}
 $('checked').onchange=()=>controls(!busy&&card?.state==='PENDING');
 async function load(){
  if(!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(id||''))throw new Error('Apri il collegamento associato alla proposta.');
  const r=await fetch(endpoint,{credentials:'same-origin',cache:'no-store',headers:{Accept:'application/json'}});
  if(!r.ok||r.redirected)throw new Error('Proposta non accessibile. Verifica account IAM e autorizzazioni.');
  const data=await r.json();card=data.card;csrfHeader=data.csrfHeader;csrfToken=data.csrfToken;
  if(card.proposalId!==id||!csrfHeader||!csrfToken)throw new Error('Risposta di revisione non valida.');
  pair($('context'),'Ente / tenant',card.tenant);pair($('context'),'Proposto da',card.proposedBy);pair($('context'),'Scade',card.expiresAt);pair($('context'),'Stato',card.state);
  $('reason').textContent=`Motivo: ${card.reason}`;grant(card.before,$('before'));grant(card.after,$('after'));$('details').textContent=JSON.stringify(card,null,2);$('review').hidden=false;
  controls(card.state==='PENDING');message(card.state==='PENDING'?'Verifica la modifica. Nessun permesso è ancora stato cambiato.':`Proposta ${card.state}. ${card.finalPolicyRef?'Policy pubblicata: '+card.finalPolicyRef:''}`);
 }
 async function decide(action){
  if(busy||card?.state!=='PENDING'||(action==='confirm'&&!$('checked').checked))return;
  busy=true;controls(false);
  try{
   const r=await fetch(endpoint+'/'+action,{method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/json','If-Match':`"${card.revision}"`,[csrfHeader]:csrfToken},body:JSON.stringify({expectedHash:card.proposedHash})});
   if(!r.ok||r.redirected){if([409,410,412].includes(r.status))throw new Error('La proposta è scaduta o il contesto è cambiato. Richiedi una nuova proposta al chatbot.');throw new Error('Conferma non registrata. Riapri la revisione e verifica la sessione IAM.');}
   const result=await r.json();card.state=result.state;message(result.state==='PUBLISHED'?`Modifica pubblicata. Policy: ${result.finalPolicyRef}`:'Proposta rifiutata. Nessun permesso modificato.');
  }catch(e){message(e.message);} // Never blindly retry a possibly completed state change.
 }
 $('decision').onsubmit=e=>{e.preventDefault();decide('confirm');};$('reject').onclick=()=>decide('reject');
 load().catch(e=>{message(e.message);$('review').hidden=true;});
})();
