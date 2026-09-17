'use strict';
(() => {
  const $ = id => document.getElementById(id);
  const params = new URLSearchParams(location.search), kind = params.get('kind') || 'property', id = params.get('id');
  let review, zoom = 1;
  const status = message => { $('status').textContent = message; };
  function text(tag, value, parent) { const el = document.createElement(tag); el.textContent = String(value); parent.append(el); return el; }
  function json(value, parent) { text('pre', JSON.stringify(value, null, 2), parent); }
  function svg(tag, attrs, parent) { const el = document.createElementNS('http://www.w3.org/2000/svg', tag); for (const [k,v] of Object.entries(attrs)) el.setAttribute(k,String(v)); parent.append(el); return el; }
  function points(g) {
    if (!g) return [];
    if (g.type === 'GeometryCollection') return g.geometries.flatMap(points);
    const flat = x => typeof x[0] === 'number' ? [x] : x.flatMap(flat);
    return flat(g.coordinates || []).filter(p => p.length >= 2 && Number.isFinite(p[0]) && Number.isFinite(p[1]));
  }
  function drawGeometry(g, cls, project) {
    if (!g) return;
    if (g.type === 'GeometryCollection') { g.geometries.forEach(x => drawGeometry(x,cls,project)); return; }
    const line = ring => ring.map((p,i) => `${i ? 'L' : 'M'}${project(p).join(',')}`).join(' ');
    const path = rings => svg('path',{d:rings.map(r=>line(r)+' Z').join(' '),class:cls},$('map'));
    switch (g.type) {
      case 'Point': { const [cx,cy]=project(g.coordinates);svg('circle',{cx,cy,r:5,class:cls},$('map'));break; }
      case 'MultiPoint':g.coordinates.forEach(p=>drawGeometry({type:'Point',coordinates:p},cls,project));break;
      case 'LineString':svg('path',{d:line(g.coordinates),class:cls,fill:'none'},$('map'));break;
      case 'MultiLineString':g.coordinates.forEach(p=>drawGeometry({type:'LineString',coordinates:p},cls,project));break;
      case 'Polygon':path(g.coordinates);break;
      case 'MultiPolygon':g.coordinates.forEach(path);break;
      default:throw new Error('Tipo geometrico non supportato per il confronto.');
    }
  }
  function draw(before,after) {
    const ps=[...points(before),...points(after)];if(!ps.length)throw new Error('Geometrie senza coordinate visualizzabili.');
    let xmin=Infinity,ymin=Infinity,xmax=-Infinity,ymax=-Infinity;
    for(const [x,y] of ps){xmin=Math.min(xmin,x);xmax=Math.max(xmax,x);ymin=Math.min(ymin,y);ymax=Math.max(ymax,y);}
    const scale=Math.min(740/Math.max(xmax-xmin,1e-8),360/Math.max(ymax-ymin,1e-8));
    const project=([x,y])=>[400+(x-(xmin+xmax)/2)*scale,210-(y-(ymin+ymax)/2)*scale];
    $('map').replaceChildren();drawGeometry(before,'shape-old',project);drawGeometry(after,'shape-new',project);
    $('map-section').hidden=false;
  }
  function setZoom(value){zoom=Math.max(.5,Math.min(16,value));const w=800/zoom,h=420/zoom;$('map').setAttribute('viewBox',`${400-w/2} ${210-h/2} ${w} ${h}`);}
  $('zoom-in').onclick=()=>setZoom(zoom*1.5);$('zoom-out').onclick=()=>setZoom(zoom/1.5);$('reset').onclick=()=>setZoom(1);
  const endpoint = kind === 'geometry' ? 'geometry/issues' : 'properties/conflicts';
  const url = `/api/udp/v1/governance/${endpoint}/${encodeURIComponent(id)}`;
  async function load() {
    if(!id && ['geometry','property'].includes(kind)){
      $('decision').hidden=true;
      const response=await fetch(`/api/udp/v1/governance/${endpoint}`,{credentials:'same-origin',headers:{Accept:'application/json'},cache:'no-store'});
      if(!response.ok)throw new Error(`Coda non disponibile (${response.status}).`);
      const items=await response.json(),list=document.createElement('ul');$('context').replaceChildren(list);
      for(const item of items){const row=document.createElement('li');const link=text('a',`${item.propertyIri} · ${item.objectId}`,row);link.href=`/trusted-human/r2f?kind=${kind}&id=${encodeURIComponent(item.id)}`;list.append(row);}
      status(items.length?'Seleziona un conflitto da esaminare. La coda mostra fino a 100 conflitti autorizzati.':'Nessun conflitto aperto visibile in questa coda.');return;
    }
    if (!['geometry','property'].includes(kind) || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(id||'')) throw new Error('Apri il collegamento di revisione associato al conflitto.');
    const response=await fetch(url,{credentials:'same-origin',headers:{Accept:'application/json'},cache:'no-store'});
    if(!response.ok)throw new Error(response.status===403?'Non hai i permessi necessari per questo confronto.':`Confronto non disponibile (${response.status}).`);
    review=await response.json();const dl=document.createElement('dl');$('context').replaceChildren(dl);
    for(const [label,value] of [['Oggetto',review.objectId],['Proprietà',review.propertyIri],['Policy',review.policyRef],['Stato',review.state]]){text('dt',label,dl);text('dd',value,dl);}
    const candidates=kind==='geometry'?[{...review.current,label:'Mantieni la geometria corrente'},{...review.candidate,label:'Accetta la geometria candidata'}]:review.candidates;
    for(const [i,c] of candidates.entries()){
      const label=document.createElement('label'),input=document.createElement('input');input.type='radio';input.name='chosen';input.required=true;input.value=c.revisionId||c.contributionId;label.append(input);text('span',` ${c.label||'Contributo '+(i+1)} · fonte ${c.sourceId}`,label);
      if(kind==='property')json(c.value,label);
      const detail=document.createElement('details');text('summary','Provenienza e riferimenti',detail);json(c.provenance,detail);label.append(detail);$('choices').append(label);
    }
    if(kind==='geometry'){draw(review.current.geometry,review.candidate.geometry);if(review.metrics){text('h2','Misure del confronto',$('context'));const metrics=document.createElement('dl');$('context').append(metrics);for(const [key,label] of [['minimum_distance_meters','Distanza minima (m)'],['current_area_m2','Area corrente (m²)'],['candidate_area_m2','Area candidata (m²)'],['topologically_equal','Equivalenza topologica']]){text('dt',label,metrics);text('dd',review.metrics[key],metrics);}}}
    $('submit').disabled=review.state!=='OPEN';$('choices').disabled=review.state!=='OPEN';$('reason').disabled=review.state!=='OPEN';
    status(review.state==='OPEN'?'Confronta i contributi, scegli quale conservare e indica il motivo.':'La revisione è già conclusa.');
  }
  $('decision').addEventListener('submit',async event=>{
    event.preventDefault();if(!review||review.state!=='OPEN')return;
    const chosen=new FormData($('decision')).get('chosen'),reason=$('reason').value.trim();if(!chosen||!reason)return;
    $('submit').disabled=true;
    const body=kind==='geometry'?{expectedCurrentRevision:review.current.revisionId,chosenRevision:chosen,reason}:{expectedCurrentRevision:review.currentRevision,chosenContribution:chosen,reason};
    try{
      const response=await fetch(url+'/decisions',{method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/json','X-OUF-CSRF':review.csrfToken},body:JSON.stringify(body)});
      if(!response.ok)throw new Error(response.status===409?'I contributi sono cambiati. Ricarica il confronto prima di decidere.':response.status===403?'Sessione o permessi non validi. Riapri la revisione.':`Decisione non registrata (${response.status}).`);
      const result=await response.json();review.state='RESOLVED';$('choices').disabled=true;$('reason').disabled=true;status(`Decisione registrata. Riferimento: ${result.decisionId}`);
    }catch(error){status(error.message);$('submit').disabled=false;}
  });
  load().catch(error=>{status(error.message);$('decision').hidden=true;});
})();
