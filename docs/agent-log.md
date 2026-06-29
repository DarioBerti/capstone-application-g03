# Registro di verifica dell'agente — EuroTransit (g03)

> **Cos'è.** Un registro dei casi concreti in cui un **artefatto prodotto da un agente** (codice,
> migrazione, test, configurazione, piano) era **errato, non sicuro o sottilmente sbagliato**, e di **come
> il team l'ha individuato e corretto**. Dalla traccia: *"Finding the agent's mistakes is the core skill the
> capstone certifies. This is graded."* Le valutazioni qui sono **autorate e possedute dal team** — l'agente
> si limita a produrre gli artefatti.
>
> Formato di ogni caso: **Artefatto → Difetto → Come l'abbiamo individuato → Correzione → Lezione.**
> Episodio più recente in cima.

### Legenda gravità

| Tag | Significato |
|---|---|
| 🟥 | Bloccante: non compila / rompe un requisito valutato / difetto di correttezza sul money path |
| 🟧 | Alto: bug reale o requisito soddisfatto solo in parte |
| 🟨 | Medio: manutenibilità / correttezza sotto condizioni particolari |
| 🟦 | Basso: minore / pulizia / hardening |

---

## Episodio 2026‑06‑29 — Riprogettazione del modello dati (Trip/Station, PK UUID, identità acquirente, void del pagamento)

**Contesto.** Abbiamo chiesto a un agente di sostituire il modello piatto di Catalog
`products(product_id, name, price_cents)` (dove origine, destinazione e orario vivevano come testo libero
dentro `name`) con un vero dominio di trasporto: `stations` + `trips`, **PK surrogata `UUID`** più una chiave
di business leggibile `code` nel formato `[CARRIER][SERVICE_NUMBER]-[YYYYMMDD]` (es. `FR9592-20260629`),
rinominare `product_id → trip_code` su tutto il money path, aggiungere identità dell'acquirente + chiave di
idempotenza lato client agli ordini, e aggiungere un *void* in Payments per chiudere il buco "denaro
trattenuto su un ordine rifiutato" (`C2`). Durante il lavoro sono emersi **sei** errori dell'agente, tutti
corretti prima del merge.

### Caso 1 — Import obsoleto di Spring Boot 3 emesso per Boot 4 🟥 (non compila)
- **Artefatto.** Il nuovo `OrdersControllerTest.kt` importava
  `org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc`.
- **Difetto.** Quello è il package di **Spring Boot 3**. Il progetto gira su **Boot 4.0.x**, dove il tipo si
  è spostato in `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`. L'agente ha usato
  la versione su cui è più addestrato.
- **Come l'abbiamo individuato.** `./gradlew test` è fallito in `compileTestKotlin` con
  `Unresolved reference 'web'`. Confrontando con il test **già funzionante** del servizio `users`
  (`UserProfileControllerTest.kt`) si vede il package corretto per Boot 4.
- **Correzione.** Import cambiato nel package `boot.webmvc.test.autoconfigure`; i test compilano e passano.
- **Lezione.** Gli agenti emettono API di framework plausibili ma obsolete con totale sicurezza. La build è
  la guardia, e un file già funzionante nello stesso repo (stessa versione di framework) è la fonte di verità
  più rapida — fidarsi di quello, non dell'import dell'agente.

### Caso 2 — Colonne `CHAR(n)` che avrebbero fatto fallire l'`Hibernate validate` all'avvio 🟧 (sottile, solo in prod)
- **Artefatto.** La prima bozza di schema dell'agente usava `CHAR(2)` per `country` e `CHAR(3)` per
  `currency` (sembra corretto per codici ISO a larghezza fissa), con le entity che li mappavano come `String`.
- **Difetto.** Flyway possiede lo schema e Hibernate gira in `ddl-auto: validate`. Una proprietà `String`
  valida contro `VARCHAR`; una colonna `CHAR/bpchar` espone un tipo JDBC diverso, quindi l'app **non sarebbe
  partita** in cluster (`SchemaManagementException: wrong column type … found [bpchar], expected [varchar]`)
  — e i nostri test su H2 **non** l'avrebbero intercettato (è esattamente il divario H2‑vs‑Postgres di `M4`).
- **Come l'abbiamo individuato.** Revisione di design contro l'invariante "Flyway possiede lo schema +
  Hibernate valida" (root `CLAUDE.md`), più il fatto che **nessuna migrazione esistente nel repo usa `CHAR`**.
- **Correzione.** Usato `VARCHAR(2)`/`VARCHAR(3)` per `country`/`currency` ovunque (catalog `trips`, orders,
  payments, users). Inoltre abbiamo rafforzato la rete di sicurezza così che questa classe di bug non possa
  più nascondersi: i servizi del money path (`orders`, `inventory`, `payments`) sono stati migrati **da H2 a
  Testcontainers Postgres**, quindi `validate` ora gira contro lo schema reale in CI.
- **Lezione.** Il DDL prodotto dall'agente diverge dalle regole di validazione dell'ORM in modi che si
  manifestano solo sul database reale. Mantenere il datasource di test fedele alla produzione (Testcontainers,
  non H2) e trattare la parità di schema‑validate come un controllo di prima classe.

### Caso 3 — Un test che sarebbe passato per il motivo sbagliato 🟨 (falsa sicurezza)
- **Artefatto.** Il test del servizio `users` per il *realm‑import gotcha* (un token senza claim `sub` deve
  restituire **400**, non un NPE/500) costruiva il token come
  `jwt().jwt { it.claims { c -> c.remove("sub") } }`.
- **Difetto.** L'unico claim di default del post‑processor `jwt()` è `sub`; rimuovendolo si ottiene un
  **set di claim vuoto**, e `Jwt.Builder.build()` lancia *"claims cannot be empty"*. La richiesta sarebbe
  fallita **dentro l'infrastruttura di test, prima di raggiungere il controller** — il test sarebbe stato
  rosso (o, con un altro setup, verde) per un motivo infrastrutturale e **non** avrebbe esercitato la guardia
  `requireSubject()` → 400 che dichiara di verificare.
- **Come l'abbiamo individuato.** Ragionando sul contratto di `Jwt.Builder` mentre scrivevamo il test
  (serve un token non vuoto), confermato dall'esecuzione.
- **Correzione.** Mantenuto un claim diverso da `sub` così che il token si costruisca ma `sub` sia davvero
  assente: `jwt().jwt { it.claim("email", "…").claims { c -> c.remove("sub") } }`. Ora il test guida davvero
  il controller a un 400.
- **Lezione.** "Il test è verde" non significa "il test verifica ciò che dice". I test scritti dall'agente
  in particolare richiedono un controllo che l'asserzione possa passare **solo** per il motivo voluto —
  verificare che il *percorso negativo raggiunga davvero il codice sotto test*.

### Caso 4 — Funzione helper `equals` di primo livello che maschera `Any.equals` 🟦 (pulizia)
- **Artefatto.** Componendo `TripFilter` in una `Specification`, l'agente ha generato una funzione di primo
  livello `private fun equals(attribute: String, value: Any): Specification<Trip>` in `CatalogService.kt`.
- **Difetto.** `equals` è il nome del metodo universale di `Any`: una funzione `equals` di primo livello è
  fonte di confusione, rischia di mascherare/ombreggiare in lettura ed è uno *smell* (un futuro chiamante
  potrebbe credere di invocare l'uguaglianza, non un builder di predicati).
- **Come l'abbiamo individuato.** Revisione di codice durante la stesura, prima dell'esecuzione.
- **Correzione.** Rinominato l'helper in `eq(...)`; intento esplicito e nessun rischio di ombreggiamento.
- **Lezione.** Gli agenti riusano nomi "ovvi" che collidono con nomi del linguaggio/runtime. Una rapida
  rilettura dei nomi degli identificatori intercetta questi *smell* prima che entrino nella codebase.

### Caso 5 — Vocabolario di seed `code` divergente tra due agenti di pianificazione paralleli 🟧 (avrebbe rotto il contratto cross‑service)
- **Artefatto.** Abbiamo lanciato in parallelo più agenti "Plan", uno per Catalog e uno per il money path.
  Entrambi hanno proposto l'insieme canonico di `code` di trip per i seed — ma **non coincidevano**: il piano
  di Catalog proponeva `FR9612-20260629` (Torino→Roma) e `IC1015-20260629` (Milano→Napoli), mentre il piano
  del money path proponeva `FR9594-20260629` e `IC1535-20260629` per le stesse tratte.
- **Difetto.** Catalog e Inventory condividono **solo** il valore `code` (database‑per‑service: il join
  "sfoglia → ordina → prenota" funziona solo se Catalog espone lo stesso `code` che Inventory semina per i
  posti). Codici divergenti avrebbero significato che ordinare un trip visto in Catalog avrebbe colpito un
  `trip_code` inesistente in Inventory → reservation impossibile, **checkout rotto nella demo**.
- **Come l'abbiamo individuato.** Riconciliando gli output dei due agenti durante la stesura del piano finale:
  la divergenza dei codici è saltata all'occhio confrontando le due tabelle "canoniche".
- **Correzione.** Definito **un'unica** tabella "Canonical shared trip vocabulary" nel piano
  (`FR9592` / `FR9612` / `IC1015` / `FR9999-20260629`) e seminato gli **stessi** codici sia in
  `catalog/V2__seed_demo_data.sql` sia in `inventory/V2__seed_demo_data.sql`.
- **Lezione.** Agenti paralleli non condividono stato: ognuno inventa in autonomia i valori "condivisi". Su
  un contratto cross‑service (qui il vocabolario `code`) serve **una** fonte di verità decisa dal team, non
  la fusione fiduciosa di output indipendenti.

### Caso 6 — Migrazione `users` additiva con nome di vincolo "indovinato" 🟨 (assunzione + sovra‑ingegnerizzazione)
- **Artefatto.** Il piano dell'agente per `users` raccomandava una migrazione **additiva** `V2` per passare
  alla PK surrogata, con passi tipo `ALTER TABLE user_profiles DROP CONSTRAINT user_profiles_pkey;` e la nota
  *"in Testcontainers sarà `user_profiles_pkey`"*.
- **Difetto.** (a) Il nome del vincolo PK era **indovinato** (assunzione sul default di Postgres) — fragile se
  divergesse. (b) L'intero ALTER per scambiare la PK è **archeologia non necessaria** in un progetto
  pre‑produzione dove ogni run di test parte da un Postgres Testcontainers pulito e non esiste storia di
  migrazione da preservare.
- **Come l'abbiamo individuato.** Revisione del piano: la policy del repo (Flyway possiede lo schema, niente
  dati di prod) rende il rewrite del baseline più semplice e meno soggetto ad assunzioni dell'ALTER.
- **Correzione.** Adottata una **policy uniforme**: riscrivere il `V1__baseline.sql` in posa (con `id UUID` PK
  + `keycloak_sub` `NOT NULL UNIQUE` + `locale`/`preferred_currency`), come per tutti gli altri servizi. Nessun
  nome di vincolo da indovinare. Annotato nel piano l'avvertenza sul checksum (ricreare eventuali DB
  KIND/cluster persistenti o `flyway repair`).
- **Lezione.** Gli agenti propongono spesso il percorso più "prudente in apparenza" (ALTER incrementali) che
  però nasconde **assunzioni** (nomi di oggetti) e complessità inutile. In pre‑produzione, con test fedeli, la
  scelta più semplice e senza assunzioni è di solito quella corretta — ma è il team a doverla decidere.

---

## Come lavoriamo con l'agente (processo, non solo i ritrovamenti)

- **La build non è negoziabile.** Ogni modifica dell'agente passa per `just test <svc>` su Testcontainers
  Postgres prima di considerarsi conclusa — qui si è chiuso il Caso 1 e la metà di verifica del Caso 2.
- **Revisione sugli invarianti.** Leggiamo l'output dell'agente contro gli invarianti documentati nel
  `CLAUDE.md` di root e dei singoli servizi (Flyway possiede lo schema, UPDATE condizionale atomico
  "never‑oversell", chiavi di idempotenza, Controller→Service→Repository) invece di fidarci che "sembri
  ragionevole" — Caso 2 e Caso 6.
- **Test fedeli.** Abbiamo rimosso H2 dai servizi del money path così che le migrazioni prodotte dall'agente
  siano validate contro lo stesso motore della produzione.
- **Coordinamento tra agenti.** Quando lanciamo agenti in parallelo, i contratti condivisi (il vocabolario
  `code`) vengono fissati dal team in un unico punto, non dedotti dalla fusione degli output — Caso 5.
- **Ownership.** L'agente redige gli artefatti; il team autora e possiede le scelte di giudizio
  (decomposizione, modello di consistenza, SLO, mappatura dei modi di guasto, ipotesi di chaos, postmortem) ed
  è responsabile di questi ritrovamenti. Questo registro è rivisto ed esteso dal team, non generato senza
  supervisione.
