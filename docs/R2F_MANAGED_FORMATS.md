# R2f — Shapefile ZIP e Microsoft Access

Stato: incremento in sviluppo. La presenza degli adapter non chiude il percorso umano o il collaudo integrato R2f.

Il percorso rimane ManagedFileAsset → FileProfile → scelta layer/tabella e chiavi → classificazioni/mapping → DRAFT → validazione/approvazione → pubblicazione immutabile. L'API create-onboarding usa `layer` anche per selezionare una tabella Access. Ogni tabella/layer selezionato ha una configurazione approvata; caricare un database non ne attiva automaticamente tutte le tabelle.

## Shapefile

Media type `application/zip`; formato del profilo `SHAPEFILE`; execution `INTERNAL_MANAGED_SHAPEFILE`, adapter `managed-shapefile-v1`.

Profilo iniziale: simple features 2D; componenti `.shp`, `.shx`, `.dbf`, `.prj`, `.cpg` coerenti. `.prj` deve risolversi a un CRS EPSG attraverso GeoTools; `.cpg` rende esplicita la codifica. File senza questi componenti o con CRS non risolvibile richiedono remediation del file: non viene inventato un CRS/encoding. Le trasformazioni restano governate dal profilo R2d approvato.

Limiti: 10 MiB compressi, 30 MiB espansi, 512 componenti, 64 layer, 10.000 record/layer, 256 attributi, 100.000 coordinate/feature nel lettore (la UDP applica inoltre i propri limiti più restrittivi). Verificati path, duplicati, lunghezze e coerenza SHP/SHX/DBF; record Z/M non ammessi. Indici, stili e XML caricati non vengono passati al lettore. Le chiavi approvate derivano dagli attributi, mai dall'ordinale del record.

Libreria: GeoTools 35.0, repository release OSGeo; licenza LGPL, da includere nell'inventario e nelle notice di distribuzione. Non richiede software ArcGIS. Jackson core 3.1.4 è fissato per correggere GHSA-r7wm-3cxj-wff9 nella dipendenza transitiva.

## Access

Media type `application/x-msaccess` o `application/vnd.ms-access`; formato `ACCESS`; execution `INTERNAL_MANAGED_ACCESS`, adapter `managed-access-v1`.

Lettore Jackcess 5.0.0 (Apache-2.0), sola lettura. Fixture `.mdb` V2000 e `.accdb` V2010; altre versioni non sono dichiarate collaudate. Tabelle locali, tipi scalari, date locali senza invenzione di fuso orario, chiavi primarie/composite, indici univoci e relazioni dichiarate. Limiti: 10 MiB, 64 tabelle, 10.000 righe/tabella, 256 colonne, 64 KiB/cella testuale, 512 relazioni.

Non vengono eseguiti query salvate, macro, VBA o espressioni. Database protetti e tabelle collegate sono rifiutati; il resolver non apre file/reti esterni. Allegati/OLE/tipi complessi non rientrano nel profilo iniziale; il profiling di una tabella che li contiene può richiedere un'esportazione tabellare compatibile. I sample Access sono redatti integralmente durante il profiling.

`metadata.relationships` e `uniqueKeys` sono **evidenze per proposte**: non assegnano IRI, non pubblicano ontologie e non materializzano edge. Il collegamento completo della proposta a Semantic Registry/THS e il collaudo di relazioni Access restano nel perimetro aperto R2f.

## Evidenze e limiti di accettazione

`AccessReaderTest`: lettura dei due formati, chiavi composite, rilettura con projection diversa, integrità originale, dati corrotti/oversize e blocco dei link esterni.

`ShapefileReaderTest`: punto, poligono con anello interno, identificativo testuale con zeri iniziali, componenti mancanti, traversal e indici incoerenti.

Questi test dei lettori non sostituiscono il collaudo source-to-serving con entrambi gli owner delle nuove pubblicazioni. Le precedenti pairwise R2a–R2e usano i loro profili storici e non attestano automaticamente Access/Shapefile.
