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

## Episodio 2026‑06‑29 — Integrazione di `dev` (resilienza Fase 2/3) nel branch `database_refactor` via merge

**Contesto.** La PR #3 (`database_refactor` → `dev`) era in conflitto: due linee di lavoro parallele avevano
riscritto entrambe il money path. `dev` (12 commit, Fase 2/3) porta la resilienza (circuit breaker
Orders→Payments + parcheggio `PAYMENT_PENDING` + recovery sweeper, bulkhead + retry, load‑shedding 429,
compensazione `void` dell'hold scaduto, lock ottimistico `@Version`, logging strutturato ECS); il nostro
branch porta refactor Postgres/Testcontainers, dominio Trip/Station + TripSeats (`productId → tripCode`),
interfacce di servizio e auth Keycloak. Abbiamo chiesto a un agente di **integrare entrambe** (merge di
`origin/dev`, niente scelte distruttive), prima delegando la mappatura a sottoagenti `Explore` in parallelo,
poi risolvendo i 20 conflitti e rifacendo i file/test aggiunti da `dev`. Sono emersi **due** errori
dell'agente, corretti prima del push.

### Caso 1 — I sottoagenti `Explore` hanno sovrastimato i conflitti e **inventato** collisioni di migrazione 🟨 (analisi speculativa)
- **Artefatto.** La "collision map" prodotta dai sottoagenti `Explore` lanciati in parallelo dichiarava: un
  conflitto testuale **CRITICO** su `orders/V1__baseline.sql` (`product_id` vs `trip_code`), **collisioni di
  numero di versione** fra le nostre migrazioni e quelle di `dev`, e che i test di `catalog`/`users` andavano
  rifatti.
- **Difetto.** Nulla di tutto ciò era vero. `dev` non ha **mai** ritoccato i baseline `V1` (ha solo
  **aggiunto** `orders/V2__add_order_version.sql` e `inventory/V3__add_reservation_version.sql`): nessun
  conflitto sul baseline, nessuna collisione di numero (le migrazioni sono additive e si impilano). E
  `catalog`/`users`/`notifications` non sono toccati dai 12 commit di `dev`, quindi fanno auto‑merge per
  intero. I sottoagenti hanno ragionato sulla **forma del diff** (`git diff base..dev`) invece che sul vero
  merge a tre vie.
- **Come l'abbiamo individuato.** Eseguendo `git merge-tree --write-tree origin/dev database_refactor`
  **prima** di scegliere una strategia: ha restituito l'insieme reale e autorevole dei conflitti — esattamente
  20 file confinati a `inventory`/`orders`/`payments`, **zero** conflitti di migrazione, `catalog`/`users`/
  `notifications` puliti.
- **Correzione.** Pianificato ed eseguito sull'insieme reale di `merge-tree`, non sulla mappa degli agenti. Le
  migrazioni si sono impilate in modo additivo (il nostro `V1` + i `V2`/`V3` di `dev`) senza alcuna
  riconciliazione manuale.
- **Lezione.** L'"analisi di merge" di un agente è una speculazione sui diff; la risposta autorevole è uno
  strumento — `git merge-tree` (un merge a secco). Eseguire prima il tool e fidarsi di **quello** più che del
  racconto dell'agente, soprattutto sulle migrazioni, dove i sottoagenti hanno allarmato in eccesso.

### Caso 2 — La risoluzione compilava e passava 37/38, ma un test era fragile rispetto all'ambiente 🟧 (osservabilità del money path)
- **Artefatto.** La risoluzione del merge ha mantenuto `MetricCardinalityGuardTest` (di `dev`) così com'era: si
  affida al fatto che la base‑url di Payments di default punti a una **porta morta**, così `payments.void()`
  cade nel fallback `UNAVAILABLE`.
- **Difetto.** Il nostro branch ha reso Payments un **resource server OAuth2**. Sulla macchina dello
  sviluppatore girava una vera istanza di Payments sulla porta di default `8082`, che ha risposto alla sonda
  **non autenticata** con **401** (un 4xx) → il gateway l'ha mappato a `FAILED`, non `UNAVAILABLE` → test
  rosso. L'assunzione del test ("nessuno è in ascolto sulla porta di default") è rotta **proprio** dalla nostra
  modifica di auth. Il codice compilava e 37/38 test di `orders` passavano; solo la suite completa l'ha fatto
  emergere.
- **Come l'abbiamo individuato.** `./gradlew test` sulla suite completa (89 test). Il report XML mostrava
  `expected: <UNAVAILABLE> but was: <FAILED>`; `lsof` ha confermato un processo `java` locale su `8082` e
  `curl` ha restituito `HTTP 401`.
- **Correzione.** Fissata la base‑url di Payments a una porta sicuramente morta (`http://localhost:1`) nelle
  `@SpringBootTest` properties del test (come già fa `PaymentsGatewayBreakerIntegrationTest`), rendendolo
  deterministico a prescindere dai processi locali. Rieseguito **con i servizi locali ancora attivi** → verde
  (38/38, totale 89/89).
- **Lezione.** "Compila e quasi tutto verde" non basta: la suite completa su Testcontainers ha intercettato un
  test con una dipendenza implicita dall'ambiente. Un test che assume "nessuno è in ascolto" diventa fragile
  appena l'auth fa sì che una chiamata reale ma non autenticata risponda 4xx — fissare esplicitamente la
  dipendenza esterna (porta morta dichiarata) invece di affidarsi all'assenza di processi locali.

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

## Episodio 2026‑07‑02 — Le dipendenze di tracing erano INERTI: mancava il modulo di auto‑config di Boot 4

- **Contesto.** Fase 4, tracing cross‑Kafka. Tutti i servizi dichiaravano da settimane
  `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp` e configuravano
  `management.tracing.sampling.probability: 1.0` e `management.otlp.tracing.endpoint` — e la roadmap diceva
  "pipeline provata, le app devono esportare gli span". Il primo tentativo (di un agente) di wiring si era
  fermato lì, dando per scontato che deps+properties bastassero.
- **Errore.** In **Spring Boot 4** l'auto‑configurazione del tracing non vive più in
  `spring-boot-actuator-autoconfigure` (com'era in Boot 3) ma nel modulo dedicato
  **`spring-boot-micrometer-tracing-opentelemetry`** (che tira `spring-boot-micrometer-tracing` +
  `spring-boot-opentelemetry`). Senza quel modulo **nessun bean `Tracer`/`Propagator`/exporter OTLP viene
  creato**: dipendenze e properties erano un no‑op silenzioso — zero span esportati, MDC senza
  traceId/spanId, e nessun errore da nessuna parte.
- **Come l'abbiamo scoperto.** Un test nuovo (`SagaCompletionMetricsTest`) che inietta `Tracer` è fallito con
  `No qualifying bean of type 'io.micrometer.tracing.Tracer'`; l'ispezione del classpath
  (`gradlew dependencies`) ha mostrato che NESSUN modulo boot di tracing era presente — quindi il problema non
  era "i test disabilitano il tracing", era che il tracing non esisteva neanche in produzione.
- **Correzione.** Aggiunto `org.springframework.boot:spring-boot-micrometer-tracing-opentelemetry` a tutti e
  sei i servizi (le properties esistenti restano valide: il modulo supporta ancora gli alias
  `management.otlp.tracing.*`). Verificato con la suite completa (`just test-all`, 6/6 verdi) e con il test
  che asserisce il `traceparent` W3C persistito nella riga di outbox.
- **Lezione.** "Dipendenza sul classpath + property configurata" NON implica "funzionalità attiva": i major
  upgrade spostano le auto‑configurazioni. La prova deve essere **positiva** (un bean iniettato, uno span
  visibile), mai l'assenza di errori.

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
