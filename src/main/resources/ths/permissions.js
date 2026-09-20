'use strict';
(() => {
 const $=id=>document.getElementById(id), id=new URLSearchParams(location.search).get('proposal');
 let card,csrfHeader,csrfToken,busy=false;
 const message=value=>{$('status').textContent=value;$('decision-status').textContent=value;};
 function context(){const dl=$('context');dl.replaceChildren();pair(dl,'Ente / tenant',card.tenant);pair(dl,'Proposto da',card.proposedBy);pair(dl,'Scade',card.expiresAt);pair(dl,'Stato',card.state);if(card.finalPolicyRef)pair(dl,'Policy pubblicata',card.finalPolicyRef);}
 function outcome(){return card.state==='PUBLISHED'?`Modifica pubblicata. Policy: ${card.finalPolicyRef}`:card.state==='REJECTED'?'Proposta rifiutata. Nessun permesso modificato.':`Proposta ${card.state}.`;}
 function text(tag,value,parent){const el=document.createElement(tag);el.textContent=String(value);parent.append(el);return el;}
 function pair(dl,label,value){text('dt',label,dl);text('dd',value,dl);}
 function grant(value,parent){parent.replaceChildren();if(!value){text('p','Nessuna abilitazione con questo identificativo.',parent);return;}const dl=document.createElement('dl');parent.append(dl);pair(dl,'Capability',value.capabilityId);pair(dl,'Soggetto',value.subjectId||'—');pair(dl,'Ruolo IAM',value.constraints?.externalRoleRef||'—');pair(dl,'Effetto',value.constraints?.effect||'ALLOW');pair(dl,'Valido da',value.validFrom);pair(dl,'Valido fino a',value.validUntil);if(value.servicePrincipalId)pair(dl,'Servizio',value.servicePrincipalId);}
 function catalogue(value,parent){
  parent.replaceChildren();const dl=document.createElement('dl');parent.append(dl);pair(dl,'Issuer IAM',value.issuer);
  for(const role of value.roles){text('h3',role.displayName+' ('+role.roleId+')',parent);for(const permission of role.permissions){text('p',permission.capabilityId,parent);if(permission.constraints)text('pre',JSON.stringify(permission.constraints,null,2),parent);}}
  text('h3','Assegnazioni',parent);for(const a of value.assignments){const item=document.createElement('dl');parent.append(item);pair(item,'Ruolo OUF',a.roleId);pair(item,a.subjectId?'Persona IAM (sub)':'Ruolo organizzativo IAM',a.subjectId||a.externalRoleRef);pair(item,'Valido da',a.validFrom);pair(item,'Valido fino a',a.validUntil);}
 }
 const endpoint=`/trusted-human/authorization/api/proposals/${encodeURIComponent(id||'')}`;
 function controls(enabled){$('confirm').disabled=!enabled||!$('checked').checked;$('reject').disabled=!enabled;$('checked').disabled=!enabled;}
 $('checked').onchange=()=>controls(!busy&&card?.state==='PENDING');
 async function load(){
  if(!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(id||''))throw new Error('Apri il collegamento associato alla proposta.');
  const r=await fetch(endpoint,{credentials:'same-origin',cache:'no-store',headers:{Accept:'application/json'}});
  if(!r.ok||r.redirected)throw new Error('Proposta non accessibile. Verifica account IAM e autorizzazioni.');
  const data=await r.json();card=data.card;csrfHeader=data.csrfHeader;csrfToken=data.csrfToken;
  if(card.proposalId!==id||!csrfHeader||!csrfToken)throw new Error('Risposta di revisione non valida.');
  context();
  $('reason').textContent=`Motivo: ${card.reason}`;if(card.afterRoles){catalogue(card.beforeRoles,$('before'));catalogue(card.afterRoles,$('after'));}else{grant(card.before,$('before'));grant(card.after,$('after'));}$('details').textContent=JSON.stringify(card,null,2);$('review').hidden=false;
  controls(card.state==='PENDING');message(card.state==='PENDING'?'Verifica la modifica. Nessun permesso è ancora stato cambiato.':outcome());
 }
 async function decide(action){
  if(busy||card?.state!=='PENDING'||(action==='confirm'&&!$('checked').checked))return;
  busy=true;controls(false);$('decision').setAttribute('aria-busy','true');
  message(action==='confirm'?'Pubblicazione in corso… Attendi l’esito.':'Rifiuto in corso… Attendi l’esito.');
  const slow=setTimeout(()=>message('La risposta sta richiedendo più tempo. Non ripetere la conferma; attendi l’esito oppure ricarica la pagina per verificarlo.'),8000);
  try{
   const r=await fetch(endpoint+'/'+action,{method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/json','If-Match':`"${card.revision}"`,[csrfHeader]:csrfToken},body:JSON.stringify({expectedHash:card.proposedHash})});
   if(!r.ok||r.redirected){if([409,410,412].includes(r.status))throw new Error('La proposta è scaduta o il contesto è cambiato. Richiedi una nuova proposta al chatbot.');throw new Error('Esito non verificato. Ricarica la pagina per leggere lo stato della proposta; se necessario accedi nuovamente con IAM. Non ripetere la conferma senza verificarlo.');}
   const result=await r.json();
   if(!['PUBLISHED','REJECTED'].includes(result.state)||(result.state==='PUBLISHED'&&!result.finalPolicyRef))throw new Error('Esito non verificato. Ricarica la pagina per leggere lo stato della proposta.');
   Object.assign(card,result);context();$('details').textContent=JSON.stringify(card,null,2);message(outcome());
  }catch(e){message(e instanceof TypeError?'Connessione interrotta: esito non verificato. Ricarica la pagina per leggere lo stato della proposta. Non ripetere la conferma senza verificarlo.':e.message);}
  finally{clearTimeout(slow);$('decision').setAttribute('aria-busy','false');$('decision-status').focus();} // Never automatically retry a possibly completed state change.
 }
 $('decision').onsubmit=e=>{e.preventDefault();decide('confirm');};$('reject').onclick=()=>decide('reject');
 load().catch(e=>{message(e.message);$('review').hidden=true;});
})();

