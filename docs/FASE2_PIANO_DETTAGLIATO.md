# Fase 2 — Resilienza applicativa: piano tecnico dettagliato (Pilastro C)

> ## ✅ FASE 2 CONCLUSA (2026-06-28)
> Tutti i punti in-scope completati e verificati: **#1–#5** (codice + 62 test verdi sui 5 servizi),
> **#6** validato **live** sul cluster `lab_g03_cluster` (probe/PDB/HPA/graceful-shutdown/degradation —
> vedi §6), **#7-codice** (7.1 coerenza metriche, 7.3 guard low-cardinality, 7.5 structured logging
> ECS con trace correlation; 2 nuovi test orders). **Fuori scope (→ Fase 4):** §7.2 dashboard RED e
> §7.4 alert burn-rate (richiedono lo stack osservabilità). **Finding aperto:** §6.3 topology spread
> soft (`ScheduleAnyway`) — scelta team da rivedere. Cluster **fermato** a fine validazione (`az aks stop`).

> Documento di lavoro del team. Spezza **ogni** punto della Fase 2 in sotto-task azionabili, ognuno
> ancorato alla **teoria del prof** (slide), alle **best practice del corso** (slide + lab) e ai
> **pattern di codice già presenti** da riusare (file:line). Generato il 2026-06-25.
> Complementare a `STATO_AVANZAMENTO.md` (dove siamo: fine Fase 1, Fase 2 = 0% implementata).

**Formato di ogni sotto-task:**
`Cosa fare` · `📘 Teoria prof` · `✅ Best practice corso` · `🔁 Pattern codice da riusare` · `⚙️ Valore/config` · `☑️ Acceptance (DoD)`

**Fonti:** deck 11 *Resilience Engineering, HA, DR* (primaria), deck 06 *Asynchronous Execution Models*,
deck 10 *Observability*, deck 01/02/09 (stability/DORA/lifecycle), Lab01-04. Codice: `capstone-application-g03/services/*`.

---

## 0. Principi guida (valgono per tutti i punti)

Il prof inquadra la resilienza così — tenerlo a mente in ogni scelta:

- **"Resilience is designed in, not bolted on"** (deck 11, slide 4). Non si aggiunge dopo: emerge da 4 proprietà che interagiscono — **Redundancy, Isolation, Recovery, Observability** (slide 4-5).
- **"Resilience is a property you prove, not assume"** (slide 41): ogni policy va dimostrata con un test/esperimento, non dichiarata.
- **Anatomia del cascading failure** (slide 6-7): un downstream lento → i thread del chiamante si riempiono → timeout → retry storm → amplifica il carico → si propaga. Le difese sono *strutturali*: bulkhead, circuit breaker, backpressure, timeout, load shedding.
- **"Instrument first, chaos second"** (slide 45): senza observability i test di resilienza danno conclusioni non interpretabili. → ogni feature di Fase 2 **deve emettere una metrica** (vedi #7).

**Anti-pattern del prof da NON commettere** (slide 14, 45):
- Retry senza backoff/jitter → retry storm.
- Liveness probe che dipende dal downstream → un hiccup del DB riavvia tutti i pod.
- "Replicas = HA" → 3 pod sullo stesso nodo/zona non sono HA.
- Chaos senza observability.
- Alert sulla CPU invece che sul sintomo visibile all'utente.

**Cosa è eseguibile ORA (locale, H2, zero dipendenze):** punti 1, 2, 4, 5 e l'observability lato codice (#7). Il punto 6 (validazione live) e dashboard/alert richiedono il cluster (Fase 3/4) → marcati come dipendenza, non bloccanti.

---

## 1. Circuit Breaker Orders→Payments + stato `PAYMENT_PENDING`  *(KEYSTONE)* — ✅ FATTO (2026-06-25)

> **Stato: IMPLEMENTATO e testato** (20/20 test verdi nel servizio `orders`). Decorazione **programmatica** del breaker via `CircuitBreakerRegistry` (non l'annotazione AOP, rischio Boot 4 — vedi `docs/agent-log.md` #2/#7); fallback `PAYMENT_PENDING`; `OrderRecoverySweeper` con bounded-park money-safe; `@Version` + migration V2 contro eventi duplicati. Difetti del primo cut (4xx park-infinito, money-leak allargato, batch-abort, @Version non testato) trovati da review adversarial e corretti — vedi `agent-log.md` #7. Restano #2/#3/#4.

Obiettivo: quando Payments è giù/lento, **contenere** il guasto (non propagarlo). L'ordine viene **parcheggiato** in `PAYMENT_PENDING` e ripreso quando il breaker richiude.

**1.1 — Aggiungere lo stato `PAYMENT_PENDING` all'enum**
- Cosa: estendere `enum class OrderStatus` con `PAYMENT_PENDING` (stato *resumable*, non terminale, tra RESERVED e CONFIRMED).
- 📘 Stato di *graceful degradation*: disabilitare/sospendere un percorso prima di farlo fallire (deck 11, slide 16).
- 🔁 `Order.kt:60` (oggi 4 valori). Diagramma a commenti `Order.kt:7-19` da aggiornare.
- ⚙️ La colonna `status VARCHAR(16)` (`V1__baseline.sql:9`) regge: `"PAYMENT_PENDING"` = 15 char. Nessuna migration necessaria (in H2 dev `ddl-auto:update`; verificare solo che non ci sia un `CHECK` sui valori — non c'è).
- ☑️ L'enum compila; gli stati terminali restano CONFIRMED/REJECTED.

**1.2 — Transizione `markPaymentPending` idempotente**
- Cosa: nuovo metodo `@Transactional` `markPaymentPending(orderId)` che porta RESERVED→PAYMENT_PENDING; idempotente (no-op se già PAYMENT_PENDING).
- 📘 Recovery come proprietà progettata (slide 4-5): il sistema deve poter tornare a uno stato noto.
- 🔁 Mirror esatto di `OrderStateMachine.kt:27-34` (`markReserved`): guard sullo stato, `updatedAt = Instant.now()`, `orders.save`. Nessun evento outbox (stato transitorio interno).
- ☑️ Da RESERVED produce PAYMENT_PENDING una sola volta; replay = no-op.

**1.3 — Circuit breaker sul gateway Payments + fallback**
- Cosa: annotare `HttpPaymentsGateway.authorize` con `@CircuitBreaker(name="payments", fallbackMethod="authorizeUnavailable")`; il fallback ritorna un sentinella `AuthorizationResult("UNAVAILABLE", false)`.
- 📘 Stati CLOSED/OPEN/HALF_OPEN; *"il breaker protegge il chiamante e il downstream insieme"*, abilita il fast-fail (deck 11, slide 17-19). Il fallback è la *safe failure behavior* senza cui il breaker è inutile (slide 17).
- ✅ Vincolo deck 10: lo stato del breaker **deve essere osservabile** (vedi #7).
- 🔁 In-place su `HttpPaymentsGateway` (`Gateways.kt:95-111`) → resta **un solo bean** di produzione e i fake `@Primary` dei test continuano a sovrascriverlo. Le deps R4j ci sono già (`build.gradle.kts:46-48`).
- ⚙️ **Importante (correttezza):** `RestClient.retrieve()` lancia su 4xx/5xx e i timeout lanciano → il breaker conta **solo** guasti di trasporto. Un `DECLINED` è un 200 con body → niente eccezione → **non** apre il breaker (è una decisione di business, non un guasto).
- ☑️ Con downstream sano: comportamento invariato. Con downstream guasto: fallback → `UNAVAILABLE`.

**1.4 — Ramo "indisponibile" nel saga**
- Cosa: in stage 2 trasformare l'`if (DECLINED)` in `when`: `DECLINED`→release+reject(PAYMENT_DECLINED); `UNAVAILABLE`→`markPaymentPending` + return (**niente release, niente throw**); altrimenti prosegui a confirm.
- 📘 *Contain, don't propagate* (slide 6-7): non si rigetta un cliente la cui carta potrebbe essere valida, e non si propaga il guasto a monte.
- 🔁 `OrderSaga.kt:48-55`. La logica di ripresa in testa (`OrderSaga.kt:32-45`) già regge PAYMENT_PENDING: non terminale (salta il guard) e `!= PENDING` (salta reserve) → al re-run ritenta solo authorize.
- ☑️ Payments UNAVAILABLE → ordine PAYMENT_PENDING, prenotazione **mantenuta**, nessuna eccezione propagata.

**1.5 — Recovery sweep (rende PAYMENT_PENDING recuperabile)**
- Cosa: nuovo `OrderRecoverySweeper` (coroutine) che periodicamente trova gli ordini PAYMENT_PENDING e richiama `saga.handle(id)`. Quando il breaker è half-open/closed e Payments torna, authorize riesce → CONFIRMED.
- 📘 Il Kafka listener ha già committato l'offset quando ha parcheggiato → non c'è redelivery: serve un loop di recupero. Lifecycle async: scope = *failure domain*, `SupervisorJob` isola i fallimenti, shutdown cooperativo su SIGTERM (deck 06, slide 47/54/96-99).
- 🔁 Mirror esatto di `ReservationReconciler.kt:28-58` (CoroutineScope+SupervisorJob, start su `ApplicationReadyEvent`, `destroy()`→`scope.cancel()`). Aggiungere `fun findByStatus(status): List<Order>` a `OrderRepository` (`Repositories.kt`).
- ⚙️ `app.recovery.poll-interval: PT5S`.
- ☑️ Un ordine PAYMENT_PENDING torna CONFIRMED dopo il recupero senza ripetere il reserve.

**1.6 — Configurazione Resilience4j del breaker**
- Cosa: blocco `resilience4j.circuitbreaker.instances.payments` in `application.yml`.
- 📘 Soglie del prof (slide 17): **apre quando >50% di fault nella finestra recente** (o P99 > 2s); poi resta aperto un breve periodo; HALF_OPEN fa passare poche chiamate di prova.
- ⚙️ Valori coerenti: `sliding-window-type: COUNT_BASED`, `sliding-window-size: 10`, `minimum-number-of-calls: 5`, `failure-rate-threshold: 50`, `wait-duration-in-open-state: PT10S`, `permitted-number-of-calls-in-half-open-state: 2`, `automatic-transition-from-open-to-half-open-enabled: true`. **Le soglie sono una scelta di team** → annotarle in `docs/design.md` decisione 7 (policy sul coding agentico: i giudizi li possiede il team).
- 🔁 Struttura `management`/`app` già in `application.yml:37-72`.
- ☑️ Il test funzionale (#5.1) verifica che le soglie siano effettivamente bound (non i default R4j).

**1.7 — Il breaker non deve influenzare le probe**
- Cosa: disabilitare l'health indicator del breaker (`management.health.circuitbreakers.enabled: false`).
- 📘 Liveness/readiness indipendenti dal downstream (slide 13-14): un breaker OPEN è il sistema che **funziona** (sta sheddando), non che fallisce.
- ☑️ Aprendo il breaker la readiness **non** flippa.

**1.8 — Property-gating del sweep nei test**
- Cosa: `app.recovery.enabled` (default true), spento nei test per non far correre il loop in concorrenza con le asserzioni.
- 🔁 Stesso pattern di `app.outbox.enabled` (`OutboxPublisher` `start()` con guard `if (!enabled) return`) e `autoStartup` del listener (`OrderPlacedListener.kt:21`).
- ☑️ Con `app.recovery.enabled=false` il bean non avvia il loop.

---

## 2. Timeout + Retry (backoff + jitter) + Bulkhead — ✅ FATTO (2026-06-26)

> **Stato: IMPLEMENTATO e testato** (27/27 test verdi nel servizio `orders`, +7 vs #1). Decorazione **programmatica**
> (come #1) via un helper riusabile `GatewayResilience` che compone `Retry( CircuitBreaker( Bulkhead( call ) ) )` con i
> `decorateCallable` per-modulo (il builder `Decorators` è in `resilience4j-all`, non a classpath → non usato).
> **Retry** transport-only (max-attempts 3, 200ms exp×2+jitter; mai 4xx/business/breaker-open/bulkhead-full), valori
> SLO-bounded (più corti dei letterali 1s/2s/4s della slide, giustificato dal budget e2e — vedi `design.md` dec.7).
> **Bulkhead** semaforo separato payments(8)/inventory(16), fail-fast; `BulkheadFullException` ignorata dal breaker.
> Scope = **entrambi i gateway** (breaker resta solo Payments). NB: il testo sotto (#2.2/#2.4) cita le **annotazioni**
> `@Retry`/`@Bulkhead` — scelta **superata** dalla decorazione programmatica di #1 (rischio AOP non-weave su Boot 4).
> Restano #3 (429) e #4 (refund/void).

Obiettivo: ogni chiamata remota bounded, ritentata in modo sicuro, isolata per dominio di guasto.

**2.1 — Timeout come "budget" derivato dall'SLO**
- Cosa: mantenere/documentare i timeout dei gateway come partizione del budget end-to-end del checkout, non come numeri arbitrari.
- 📘 *"Every call must have a deadline… derive each downstream timeout from the caller's end-to-end deadline, leaving time for fallback or retry"* (slide 22).
- 🔁 Già presenti: `Gateways.kt:33-37` (connect 2s / read 5s). Allinearli all'SLO di `docs/slo.md` (ack p99 ≤ 300ms, e2e p95 ≤ 5s).
- ☑️ Nessuna chiamata senza deadline; i timeout sono giustificati nel doc.

**2.2 — Retry con backoff esponenziale + jitter**
- Cosa: `@Retry(name="payments")` (e su Inventory dove idempotente) con backoff esponenziale e jitter, max tentativi e cap.
- 📘 Valori del prof (slide 22): **1s, 2s, 4s, 8s…, cap a 30s, max 5 tentativi, + jitter** per evitare ondate sincronizzate.
- ⚙️ R4j: `resilience4j.retry.instances.payments`: `max-attempts: 3` (≤5), `wait-duration: PT1S`, `enable-exponential-backoff: true`, `exponential-backoff-multiplier: 2`, `enable-randomized-wait: true` (jitter), `retry-exceptions:` solo trasporto (IOException/HttpServerError), **non** le eccezioni di business.
- ☑️ Su guasto transitorio ritenta con attese crescenti randomizzate; si ferma entro il cap.

**2.3 — Retry SOLO su operazioni idempotenti**
- Cosa: abilitare il retry esclusivamente sulle chiamate idempotenti.
- 📘 *"Retry only when the operation is idempotent, deduplicated, or protected by an idempotency key"* (slide 22).
- 🔁 Già garantito: Inventory dedup `UNIQUE(order_id, product_id)`, Payments `UNIQUE(order_id)` (decisione design #6). Un retry di `authorize`/`reserve` non doppia addebiti/prenotazioni. **Documentare questo legame** è metà del punteggio del Pilastro B/C.
- ☑️ Test: N retry della stessa authorize → un solo addebito (#5.3).

**2.4 — Bulkhead per isolare i pool**
- Cosa: `@Bulkhead(name="payments")` e `@Bulkhead(name="inventory")` distinti, così un Payments lento non esaurisce le risorse usate per Inventory.
- 📘 *"Isolate resource pools per downstream service. A slow service cannot consume all threads/connections… limiting blast radius, trading efficiency for isolation"* (slide 20).
- ⚙️ `resilience4j.bulkhead.instances.{payments,inventory}`: `max-concurrent-calls` dimensionato; valutare `THREADPOOL` vs `SEMAPHORE` (semaforo è sufficiente per chiamate sync bloccanti).
- 🔁 Le due dipendenze sono già separate dietro interfacce (`InventoryGateway`/`PaymentsGateway`, `Gateways.kt:23-31`).
- ☑️ Saturando Payments, le chiamate a Inventory continuano (test/observabile).

**2.5 — Ordine di composizione delle policy R4j**
- Cosa: ordine di applicazione degli aspect: `Bulkhead → TimeLimiter → RateLimiter → CircuitBreaker → Retry` (default Resilience4j; il Retry avvolge il CircuitBreaker).
- 📘 Coerente con l'anatomia del cascade: prima si limita la concorrenza (bulkhead), poi si fast-fail (breaker), il retry è l'outer-most ma bounded.
- ☑️ Documentare l'ordine scelto in `docs/design.md` (decisione 7).

**2.6 — Anti-pattern retry storm**
- Cosa: il retry deve coesistere col breaker; quando il breaker è OPEN, il retry **non** deve martellare.
- 📘 *"Retrying without backoff or circuit breaking converts a partial failure into a full retry storm"* (slide 45).
- ☑️ Con breaker OPEN, `authorize` fa fast-fail → fallback, non 5 tentativi inutili.

**2.7 — Metriche di retry/bulkhead** → vedi #7.

---

## 3. Backpressure / Load shedding (HTTP 429) — ✅ FATTO (2026-06-26)

> **Stato: IMPLEMENTATO e testato.** Rate limiter Resilience4j sull'edge `POST /api/orders`, applicato
> **PROGRAMMATICAMENTE** via `RateLimiterRegistry` (mirror di `GatewayResilience`, stesso motivo Boot-4 AOP —
> agent-log #2), in un nuovo componente `OrdersIntakeRateLimiter` (`withPermit{}`); oltre budget → shed:
> counter `orders_intake_shed_total` + `IntakeOverloadedException` → mappata dal controller a **HTTP 429 +
> `Retry-After`** (header derivato dalla finestra del limiter, mai un magic number). `timeout-duration: PT0S`
> = rifiuto immediato, niente coda. Health indicator del limiter disabilitato (`management.health.ratelimiters.enabled:
> false`) come per il breaker (#1.7): lo shedding è il sistema che funziona, non deve flippare la readiness.
> Test funzionale via MockMvc (202 poi 429+Retry-After+counter, deterministico con budget 1/PT1M). Restano #4 (refund/void) e #5.

Obiettivo: sotto sovraccarico, rifiutare lavoro in modo controllato invece di accodare fino al collasso.

**3.1 — Rispondere 429 + `Retry-After` sull'ingresso sotto sovraccarico**
- Cosa: limitare il rate dell'entry sync `POST /api/orders`; oltre soglia restituire **HTTP 429** con header `Retry-After`.
- 📘 *"Backpressure is not failure: it is controlled refusal"*; *"queues just delay the failure"*; segnali: **429 (con Retry-After)**, 503, cancellazione async (slide 24).
- ⚙️ `@RateLimiter(name="orders-intake")` su `OrdersController.placeOrder`, oppure rate limit a livello bulkhead; `resilience4j.ratelimiter.instances`: `limit-for-period`, `limit-refresh-period`, `timeout-duration: 0` (rifiuta subito). Mappare l'eccezione `RequestNotPermitted` → `429` con `Retry-After`.
- 🔁 Entry point `OrdersController` (handler 400/404 già presenti, stesso stile).
- ☑️ Oltre soglia il servizio risponde 429 invece di accodare.

**3.2 — Evitare il queue-to-collapse**
- Cosa: code bounded; nessuna coda illimitata sul percorso critico.
- 📘 *"Prefer bounded queues"* (slide 7).
- 🔁 Il consumo Kafka è già a `enable-auto-commit=false` + ack manuale (assorbe burst). L'outbox relay drena a batch di 50 (`OutboxPublisher`). Documentare che il buffering è bounded.
- ☑️ Nessun punto del percorso accumula lavoro illimitato in memoria.

**3.3 — Cancellazione cooperativa del lavoro abbandonato**
- Cosa: se il chiamante rinuncia (timeout/cancel), interrompere il lavoro a valle.
- 📘 *"If the caller gives up, stop working. Work done for a cancelled request wastes resources"* (slide 24); cancellazione cooperativa delle coroutine (deck 06, slide 58-60).
- 🔁 I loop coroutine già sospendono fuori dal `try` per onorare la cancellazione (`ReservationReconciler.kt:40-49`).
- ☑️ Allo shutdown nessun task orfano (già vero per reconciler/sweeper).

**3.4 — Metrica di shedding** → `orders_intake_shed_total` (vedi #7).

**3.5 — Dove NON mettere il 429**
- Cosa: il 429 va sull'**ingresso** (intake), non sulle chiamate interne del saga (lì agiscono breaker/bulkhead). Documentare la distinzione.
- ☑️ Le chiamate interne usano breaker/bulkhead; l'edge usa rate limit/429.

---

## 4. Compensazione refund/void per `RESERVATION_EXPIRED` — ✅ FATTO (2026-06-26)

> **Stato: IMPLEMENTATO e testato** (orders 31/31, payments 11/11 verdi). Void = **transizione idempotente
> `AUTHORIZED → VOIDED`** sull'aggregato Payments esistente (`PaymentsService.void` + `POST
> /api/payments/authorizations/{orderId}/void`), **non** una tabella reversal; **nessuna migration** (`VOIDED`
> entra in `status VARCHAR(16)` senza CHECK); **nessun evento** (eventi Payments best-effort, nessun consumer).
> Il gateway `payments.void` riusa l'istanza R4j `payments` (breaker+bulkhead+retry) con la **stessa ladder
> fallback a 4 rami** di `authorize` (404→`NOT_FOUND` benigno). **Void fallito = Opzione B (decisione team):** non
> si rigetta con addebito vivo — si **parcheggia** (`PAYMENT_PENDING`) e `OrderRecoverySweeper` ri-guida la saga e
> **ritenta il void** fino a `VOIDED`. Anche al confine `max-park` il sweep rigetta solo a money-state risolto
> (`VOIDED`/`NOT_FOUND`/`DECLINED`), altrimenti (Payments irraggiungibile) **resta parcheggiato e ritenta**
> (`orders_recovery_total{outcome=void_retry}`) — money-safety sopra la bounded-retention (audit, `agent-log.md` #11).
> Ramo stage-2 `"VOIDED" → reject(RESERVATION_EXPIRED)` per la crash-window. Metriche:
> `orders_compensations_total{type="refund"}` + `payments_voids_total{outcome}`. Lost-update guard (`@Version`)
> differito (money-safe senza, manterrebbe migration-free). Il primo design (reject-always + contatore) **osservava**
> solo il leak: colto da review avversariale a 3 lenti → vedi `docs/agent-log.md` #9. Restano #5 (test) e #6/#7.

Obiettivo: chiudere il buco del saga — se la prenotazione è scaduta dopo l'addebito, va stornato.

**4.1 — Chiudere il TODO nel saga**
- Cosa: nel ramo `confirmation.status != "CONFIRMED"`, prima del `reject(RESERVATION_EXPIRED)`, invocare la compensazione su Payments (storno/void).
- 🔁 `OrderSaga.kt:58-64` (oggi `// TODO Phase 2: compensate the charge (refund/void on Payments)`).
- 📘 Saga = compensazioni sui fallimenti (decisione design #3); la compensazione mantiene l'invariante "nessun addebito senza posto".
- ☑️ Hold scaduto post-authorize → storno chiamato, poi REJECTED(RESERVATION_EXPIRED).

**4.2 — Metodo gateway `void/refund` idempotente**
- Cosa: aggiungere `fun void(orderId: String): ...` a `PaymentsGateway` + impl HTTP.
- 🔁 Mirror del pattern `Gateways.kt:95-111` (RestClient con timeoutFactory, DTO mirror). Idempotente come `release` di Inventory (`InventoryGateway.release`, `Gateways.kt:85-92`).
- ☑️ Chiamare void due volte = stesso esito (no doppio storno).

**4.3 — Endpoint idempotente lato Payments**
- Cosa: `POST /api/payments/authorizations/{orderId}/void` che annulla l'autorizzazione una sola volta.
- 🔁 Stesso pattern di idempotenza già in `PaymentsService` (fast-path su record esistente, `UNIQUE(order_id)`).
- ☑️ Void idempotente, osservabile via `GET` (come l'authorize esistente).

**4.4 — Retry/breaker anche sulla compensazione**
- Cosa: la chiamata void è remota → bounded timeout + (eventuale) retry idempotente.
- 📘 Ogni chiamata remota ha deadline e, se idempotente, può ritentare (slide 22).
- ☑️ Un void fallito transitoriamente viene ritentato senza doppio storno.

**4.5 — Metrica + test** → `orders_compensations_total{type="refund"}` (#7) + test (#5.4).

---

## 5. Test funzionali della resilienza — ✅ FATTO (2026-06-26)

> **Stato: COMPLETO** (62 test verdi sui 5 servizi). Gran parte costruita **interleaved** con #1-#4; una gap-analysis
> a 3 lenti + verifica manuale ha confermato 4/6 criteri già coperti e chiuso i 3 gap residui. **5.1** CB funzionale →
> `PaymentsCircuitBreakerTest` (OPEN→short-circuit→half-open→CLOSED deterministico + 5 soglie yml non-default pinnate;
> + `PaymentsGatewayBreakerIntegrationTest`). **5.2** parking/recovery → `OrderSagaTest`. **5.3** retry+idempotenza →
> **nuovo** `PaymentsRetryIdempotencyIntegrationTest` (stub idempotente + risposta persa post-commit → ritrasmissione →
> **1 solo addebito**; **falsificabile**: senza dedup diventa rosso) che cabla il legame slide-22 in un solo flusso
> (prima provato solo per composizione). **5.4** compensazione → `OrderSagaTest` (void-before-reject). **5.5** gating →
> orders già completo (14/14 `app.recovery.enabled=false`); chiuso il buco **inventory**: aggiunto enabled-gate a
> `ReservationReconciler` (mirror di `OrderRecoverySweeper`) + `inventory.reservation.enabled=false` nei 3 test inventory
> (race-free per gating, non più per timing). **5.6** tracciabilità → `capstone-configuration-g03/docs/chaos/test-traceability.md`
> (mappa ogni test → esperimento 01-05 + ipotesi condivisa) + `@Tag("chaos-NN")` sui test mappati. Vedi `agent-log.md` se emergono note.

Obiettivo del prof: **provare il comportamento**, non l'esistenza dei bean. Ogni test = un'ipotesi verificabile (metodo scientifico del chaos, slide 41).

**5.1 — Test funzionale del circuit breaker**
- Cosa: con il `CircuitBreakerRegistry` configurato, guidare l'istanza `payments`: N fault → **OPEN** + chiamate short-circuited (`CallNotPermittedException`) → `transitionToHalfOpenState()` → 2 successi → **CLOSED**. Asserire anche che le soglie bound siano quelle di `application.yml` (non i default).
- ✅ Supera il test attuale "solo bean esiste" (`OrdersApplicationTests.kt:31-38`) — Lab01 DoD: *"CI without meaningful tests is only automated compilation"*.
- ☑️ Apertura→fallback→half-open→closed dimostrati in modo deterministico (senza attese wall-clock).

**5.2 — Test saga parking/recovery**
- Cosa: `FakePayments.status = "UNAVAILABLE"` → ordine PAYMENT_PENDING, **inventory NON rilasciato**, nessun reject; poi `"AUTHORIZED"` + re-handle → CONFIRMED, reserve non ripetuto.
- 🔁 Riusa i fake `@Primary` (`OrderSagaTest.kt:30-59`), `@SpringBootTest` con `app.recovery.enabled=false`.
- ☑️ Parcheggio e recupero verificati end-to-end in-process.

**5.3 — Test retry + idempotenza (no doppio addebito)**
- Cosa: simulare fault transitori che innescano retry → verificare un solo addebito/una sola prenotazione.
- 📘 Retry sicuro solo perché idempotente (slide 22) → questo test lo **prova**.
- 🔁 Pattern dei test di concorrenza già esistenti (`InventoryConcurrencyTest`).
- ☑️ N retry → 1 effetto.

**5.4 — Test compensazione refund**
- Cosa: hold scaduto post-authorize (`FakeInventory.confirmStatus="EXPIRED"`) → assert void chiamato + REJECTED(RESERVATION_EXPIRED).
- 🔁 Estende lo scenario `hold expired` già presente (`OrderSagaTest.kt:147-157`), aggiungendo l'assert sul void.
- ☑️ Lo storno avviene prima del reject.

**5.5 — Gating anti-race nei test**
- Cosa: tutti i `@SpringBootTest` che bootano il context settano `app.recovery.enabled=false` (oltre a outbox/kafka già spenti).
- 🔁 Blocco `properties` di `OrderSagaTest`/`OrdersApplicationTests`/`OutboxPublisherTest`.
- ☑️ Nessun loop di background interferisce con le asserzioni.

**5.6 — Mappare ogni test a un'ipotesi di chaos (Fase 6)**
- Cosa: annotare quale esperimento Chaos Mesh ciascun test anticipa (es. 5.1 ↔ chaos #1 latenza Payments).
- 📘 *"Resilience is a property you prove"* (slide 41); il test in-process è il primo livello, il chaos sul cluster il secondo.
- ☑️ Tracciabilità test→esperimento documentata in `docs/chaos/`.

---

## 6. Validazione Kubernetes + graceful degradation — ✅ VALIDATO LIVE (2026-06-28)

Nota: **questi artefatti sono GIÀ scritti nel chart** (Fase 0/1) e il **codice prerequisito esiste già** (es. §6.5: entrambi i `destroy()` cooperativi sono in sorgente). Qui si **valida** che funzionino col codice. Nessuna modifica di codice in §6.

**Esiti validazione live** (cluster `lab_g03_cluster`, AKS 3× nodi, 2026-06-28):
- **6.1 ✅** — `curl /actuator/health/readiness` = `{db, readinessState}` status UP, **niente kafka**; `/liveness` = solo `{status:UP}`. Endpoint separati.
- **6.2 ✅** — `kubectl drain` del nodo `…00000c`: le app eurotransit evictate **una alla volta** rispettando il PDB, `get deploy` resta 2/2 (money-path mai a 0); osservati i rifiuti `Cannot evict pod as it would violate the pod's disruption budget` su Kafka/coredns/metrics-server. CNPG `orders-db` ha fatto **failover pulito** (primary → `orders-db-2`), ArgoCD tornato Synced&Healthy dopo `uncordon`.
- **6.3 ⚠️ FINDING** — `topologySpreadConstraints` presente (maxSkew 1, `kubernetes.io/hostname`) ma `whenUnsatisfiable: ScheduleAnyway` (soft): live **entrambe le repliche payments su `…00000d`**. Lo spread è preferenza, non garanzia → un guasto di quel nodo perde tutte le payments. Vedi nota sotto.
- **6.4 ✅** — `kubectl get hpa` = **solo** `catalog` e `orders` (cpu vs 70%, min2/max4); inventory/payments **senza** HPA. Scale-up live non eseguito (costo AKS); la scelta di design è ciò che conta.
- **6.5 ✅** — kill di una replica `orders` sotto traffico money-path: **45/45 richieste `202`**, zero 5xx (nessuna richiesta in volo persa, deploy resta 2/2). Log del pod morente: `Commencing graceful shutdown` → `cancelling outbox relay (cooperative shutdown)` → `cancelling order recovery sweep (cooperative shutdown)`.
- **6.6 ✅** — `notifications` scalato a `0/0` → checkout `POST /api/orders` = `PENDING → CONFIRMED` in ~4s. Checkout completa con Notifications interamente giù.

> **Finding 6.3 (scelta team da rivedere):** passare a `whenUnsatisfiable: DoNotSchedule` garantirebbe repliche su nodi distinti, ma su 3 nodi rischia pod non-schedulabili durante un drain. Trade-off availability vs hard-spread: decisione del team (annotare in `docs/design.md`).
>
> **Nota §7.5 live:** i pod in cluster girano l'immagine di Fase 3 (`3af035e`), **precedente** allo structured logging → i log live sono in formato plain. Il JSON ECS comparirà dopo un rebuild CI con la nuova config `application-postgres.yml`. La prova del meccanismo è nel test `OrdersStructuredLoggingTest` (verde in locale).

**6.1 — Probe deliberate**
- Cosa: verificare readiness = `readinessState + db` (Kafka **escluso**); liveness = solo stato processo; startup grace per il boot JVM.
- 📘 Liveness solo stato interno; readiness può fallire in overload e droppa traffico; *"never use the same endpoint for liveness and readiness"* (slide 13-14).
- 🔁 Già in `application.yml:48-66` (gruppo readiness `readinessState,db`) e nel chart `deployments.yaml` (liveness/readiness/startup separate).
- ☑️ Un blip di Kafka **non** toglie il pod dal LB; un downstream lento non riavvia il pod.

**6.2 — PodDisruptionBudget**
- Cosa: validare PDB `minAvailable: 1` sui servizi critici (≥2 repliche).
- 📘 *"3 replicas, minAvailable: 2 → permits at most one voluntary disruption"* (slide 11); protegge solo da disruzioni *volontarie* (drain/upgrade).
- 🔁 `poddisruptionbudgets.yaml` già presente.
- ☑️ Un node drain non azzera il percorso critico.

**6.3 — Topology spread**
- Cosa: validare `topologySpreadConstraints` (maxSkew) sui critici.
- 📘 Senza spread lo scheduler può impacchettare tutte le repliche in una zona → un guasto zona le perde tutte (slide 9, 12). *"Replication protects only if replicas are across independent failure domains."*
- 🔁 Già nel chart su orders/inventory.
- ☑️ Repliche distribuite tra nodi/zone.

**6.4 — HPA solo dove il collo di bottiglia è CPU**
- Cosa: confermare HPA su catalog/orders, **non** su inventory/payments (lì il limite è DB/PSP).
- 📘 + ✅ *"HPA scaling on CPU may miss queue depth or downstream latency… scale on saturation, not CPU"* (deck 10, slide 24/40). Scalare inventory sulla CPU sarebbe resilienza illusoria.
- 🔁 `hpa.yaml` già limitato a catalog/orders.
- ☑️ La scelta è giustificata nel doc (no HPA su risorsa contesa da DB).

**6.5 — Graceful shutdown (SIGTERM / preStop drain)**
- Cosa: validare `terminationGracePeriodSeconds: 30` + `preStop sleep 5`; le coroutine si cancellano cooperativamente.
- 📘 Sequenza shutdown: SIGTERM → readiness false → grace → terminate; la cancellazione propaga ai figli (deck 06, slide 96-99). *"Cancellation is not an error: it is a normal operational signal."*
- 🔁 Drain già nel chart; il codice è **già completo**: `ReservationReconciler.destroy()` (`ReservationReconciler.kt:63`) **e** `OrderRecoverySweeper.destroy()` (`OrderRecoverySweeper.kt:140`) — più `OutboxPublisher.destroy()` — fanno `scope.cancel()` cooperativo (la dicitura "futuro" era stale).
- ☑️ Allo scale-down nessuna richiesta in volo persa, nessun task orfano. **Evidenza già visibile:** col structured logging attivo, `kubectl logs` del pod terminato mostra `cancelling order recovery sweep (cooperative shutdown)` in JSON ECS.

**6.6 — Degradazione: Notifications giù → checkout OK**
- Cosa: validare che con Notifications spenta il checkout completi comunque.
- 📘 *"Disable optional features before core flows fail"* (slide 16); Notifications è fully-async, fuori dal percorso critico.
- 🔁 Eventi notification = informativi/best-effort (NOTE_IT.md), non load-bearing.
- ☑️ Checkout CONFIRMED anche con Notifications down.

---

## 7. Cross-cutting — Observability della resilienza (deck 10)

Principio: *"You cannot make a system resilient without knowing it is failing"* (slide 40). **Ogni** policy di Fase 2 emette un segnale.

**7.1 — Una metrica per ogni policy** — ✅ FATTO
- Cosa: esporre su `/actuator/prometheus`: `resilience4j_circuitbreaker_state{name="payments"}` e `_calls`, `resilience4j_retry_calls`, `resilience4j_bulkhead_available_concurrent_calls`, `orders_intake_shed_total`, `orders_compensations_total{type="refund"}`, `orders_payment_failures_total{outcome="unavailable"}`.
- 🔁 `resilience4j-micrometer` già a classpath (`build.gradle.kts:48`) → i meter del breaker si registrano da soli. I counter custom seguono `meterRegistry.counter(...)`: `orders_placed_total` (`OrderService.kt:45`), `orders_compensations_total{type}` (`OrderSaga.kt:36`/`OrderRecoverySweeper.kt:66`), `orders_recovery_total{outcome}` (`OrderRecoverySweeper.kt:63`), `orders_intake_shed_total` (`OrdersIntakeRateLimiter.kt:25`), `orders_payment_failures_total{outcome}` (`Gateways.kt:118`), `payments_authorizations_total`/`payments_voids_total{outcome}`, `inventory_reservations_total{outcome}`.
- ☑️ Ogni feature è visibile come serie temporale.
- ⚠️ Nota coerenza (2026-06-28): il piano citava `orders_payments_unavailable_total`; il codice emette il più ricco `orders_payment_failures_total{outcome ∈ unavailable|client_error|bulkhead_full}` (`Gateways.kt:118`). Allineato il nome al codice (no rename: il counter è già usato dai test e l'outcome-tag copre i tre casi).

**7.2 — RED sul money-path**
- Cosa: Rate / Errors / Duration per il checkout (per servizio e per dipendenza).
- 📘 RED misura la qualità lato utente (deck 10, slide 21); SLI emessi da Orders (solo lui vede la state machine — `docs/slo.md`).
- ☑️ Dashboard (Fase 4) risponde a *"dove è andato il tempo / dove ha fallito?"*.

**7.3 — Low cardinality** — ✅ FATTO
- Cosa: label ammesse = `outcome`, `reason`, `dependency`, `status` (+ `type`). **Vietate** `orderId`, `traceId`, `userId`.
- 📘 *"High-cardinality labels destroy metrics systems"* (slide 15).
- 🔁 Convenzione già usata: `..._total{outcome=...}` (Inventory/Payments).
- ☑️ Nessuna label ad alta cardinalità. **Guard di regressione:** `MetricCardinalityGuardTest` (orders) asserisce che ogni meter custom porta solo tag-key ammesse e nessun tag-value UUID-shaped.

**7.4 — Alert symptom-based / burn-rate**
- Cosa: gli alert (Fase 4) si basano su burn-rate dell'SLO checkout, **mai** sulla CPU; lo stato del breaker è osservabile ma non necessariamente paging.
- 📘 Buono: *"checkout SLO burn rate > 14× su 5m e 6× su 1h"*; cattivo: *"CPU > 80%"* (slide 20). Multi-window burn-rate già deciso in `docs/slo.md`.
- ☑️ Definizioni di alert pronte per Fase 4, ancorate al sintomo.

**7.5 — Metriche/log/trace = 3 domande** — ✅ FATTO (2026-06-28)
- Cosa: metriche per alert/SLO; log strutturati (con traceId) per il "perché"; trace per "dove è il tempo".
- 📘 Slide 9; tracing che attraversa Kafka via header (decisione design #8).
- 🔁 OTLP già configurato; **structured logging nativo Spring Boot 4** ora attivo: `logging.structured.format.console=ecs` nel profilo cluster (`application-postgres.yml` dei 4 servizi con DB; base `application.yml` per notifications che non ha profilo postgres). In cluster ogni servizio emette JSON ECS con `trace.id`/`span.id` presi dall'MDC di micrometer-tracing → correlazione log↔trace. Nessuna dipendenza nuova, nessun `logback-spring.xml`.
- ☑️ Correlazione log-metric-trace possibile sul money-path. **Test:** `OrdersStructuredLoggingTest` prova che, dentro uno span attivo, la riga di log è JSON valido e contiene il trace id.

---

## 8. Definition of Done — Fase 2 (Pilastro C)

Mappa ai punti ☐ di `capstone-configuration-g03/docs/capstone-dod.md` (oggi tutti todo). Spuntare quando:

| DoD item (Pillar C) | Coperto da | Spunta quando |
|---|---|---|
| Circuit breaker Orders→Payments (open/half-open + fallback) | #1 | test #5.1 verde + metrica #7.1 |
| Bulkheads isolano i pool | #2.4 | test isolamento + metrica bulkhead |
| Timeout + retry bounded con backoff **e jitter** | #2.1-2.2 | config R4j + test #5.3 |
| Backpressure / load shedding (429) | #3 | 429 osservato + metrica #3.4 |
| Graceful degradation (Notifications giù → checkout OK) | #6.6 | ✅ validato live 2026-06-28 (notifications=0 → CONFIRMED) |
| Probe deliberate; PDB; topology spread; HPA | #6 | ✅ validato live 2026-06-28 (drain/scale/curl; finding §6.3) |

**Vincoli di consegna del corso** (Lab01-04, deck 02): `just verify` verde, `just test-all` verde, **CI verde**, immagini immutabili, **zero credenziali cluster in CI**, ogni scelta non ovvia annotata in `docs/` (è lì che si prende il voto). Aggiornare anche `docs/design.md` (decisione 7: policy CB/retry/bulkhead) e `docs/agent-log.md` se emerge un errore reale dell'agente durante l'implementazione.

---

## 9. Appendici

### A — Valori concreti del prof (deck 11)
| Parametro | Valore | Slide |
|---|---|---|
| Circuit breaker — apertura | error rate **> 50%** nella finestra (o **P99 > 2s**) | 17 |
| Retry — backoff | **1s, 2s, 4s, 8s…**, cap **30s**, max **5** tentativi, **+ jitter** | 22 |
| Timeout | ogni call bounded, derivato dal budget end-to-end | 22 |
| Backpressure | **HTTP 429 + Retry-After** (controlled refusal) | 24 |
| PDB | 3 repliche, **minAvailable: 2** → max 1 disruzione volontaria | 11 |
| Topology spread | maxSkew tra zone/nodi | 12 |
| RTO/RPO/MTTR | dal business, non dall'engineering | 32-33 |
| Chaos | steady state → hypothesis → inject → observe → conclude | 41 |

### B — Indice teoria (deck → slide)
Circuit breaker 11/17-19 · Bulkhead 11/20 · Timeout 11/22 · Retry 11/22 · Backpressure 11/24 · Graceful degradation 11/16 · Probe 11/13-14 · PDB/topology 11/9-12 · RTO/RPO 11/32-33 · Chaos 11/41-45 · Cascading failure 11/4-7 · 4 proprietà 11/4-5 · Structured concurrency/shutdown 06/47-60,96-99 · RED/USE 10/21 · SLO/error-budget 10/5-6 · Cardinalità 10/15 · Alert burn-rate 10/20.

### C — Indice codice (pattern da riusare)
- Coroutine loop lifecycle → `services/inventory/.../ReservationReconciler.kt:28-58`
- Transizioni transazionali idempotenti → `services/orders/.../OrderStateMachine.kt:27-75`
- Saga (stage 2 + TODO refund) → `services/orders/.../OrderSaga.kt:48-64`
- Gateway interfaccia + timeout factory → `services/orders/.../Gateways.kt:23-37,85-111`
- Property-gating → `OutboxPublisher` (`enabled`), `OrderPlacedListener.kt:21`, `application.yml:37-46`
- Metriche → `OrderService.kt:41`, `InventoryService`/`PaymentsService` (`..._total{outcome}`)
- Test fakes + assert → `OrderSagaTest.kt:30-59`, `OrdersApplicationTests.kt:31-38`
- Deps R4j → `services/orders/build.gradle.kts:46-48`, `services/payments/build.gradle.kts:38-40`
- Schema → `services/orders/.../V1__baseline.sql:9` (`status VARCHAR(16)`)

### D — Ordine di esecuzione consigliato
1. **#1 Circuit breaker + PAYMENT_PENDING** (keystone; abilita chaos #1).
2. **#2 Timeout/Retry/Bulkhead** (completa la difesa sulla stessa chiamata).
3. **#4 Compensazione refund** (chiude il TODO del saga).
4. **#3 Load shedding 429** (difesa all'edge).
5. **#5 Test funzionali** — in parallelo a ogni feature (1-4), non alla fine.
6. **#7 Observability lato codice** — insieme a ogni feature (metrica per policy).
7. **#6 Validazione K8s** — **dipende dalla Fase 3** (cluster); le scelte si rivedono ora, la prova live dopo.

**Eseguibile ORA in locale (H2, zero dipendenze):** #1, #2, #4, #5, #7-codice → `just test-all` / `just run orders` + `GET /actuator/prometheus`.
**Richiede cluster (Fase 3/4):** #6 (prova live di probe/PDB/HPA/drain) e dashboard/alert di #7.2/7.4.

---

## 10. Lavoro in parallelo (2 sviluppatori in contemporanea)

**Principio:** il conflitto NON è teorico, è sui **file condivisi**. Il "core conteso" del money-path è formato da
**3 file**: `OrderSaga.kt`, `Gateways.kt`, `services/orders/.../application.yml`. Chi tocca questi tre = **un solo owner**.
Tutto ciò che non li tocca è parallelizzabile senza merge conflict.

### Mappa dei 7 punti: parallelizzabile?

| Punto | Parallelizzabile | File toccati | Conflitto con | Track |
|---|---|---|---|---|
| **#1** CB + PAYMENT_PENDING | ✅ con #3 / #4a / #6 — ❌ con #2 | `Order.kt`, `OrderStateMachine.kt`, `OrderSaga.kt` (stage 2), `Gateways.kt` (authorize), `OrderRecoverySweeper.kt` (nuovo), `Repositories.kt`, `application.yml` (circuitbreaker+recovery) | #2, #4b | **A** |
| **#2** Retry + Bulkhead | ❌ con #1 (**va DOPO**) | `Gateways.kt` (stesso `authorize`), `application.yml` (stesso blocco resilience4j) | #1, #4b | **A** |
| **#3** Backpressure 429 | ✅ con tutto | `OrdersController.kt`, `application.yml` (ratelimiter) | nessuno (file/metodo diversi) | **B** |
| **#4** Refund/void | ⚠️ **SPLIT** (vedi sotto) | 4a: servizio Payments · 4b: `OrderSaga.kt`/`Gateways.kt` | 4b con #1/#2 | **A**+**B** |
| **#5** Test funzionali | ↪️ segue ogni feature (interleaved) | test di Orders / Payments | la feature relativa | chi fa la feature |
| **#6** Validazione K8s | ✅ con tutto (repo diverso) | chart in `capstone-configuration-g03` (review) | nessuno (codice app intatto) | **B** |
| **#7** Observability | ↪️ interleaved + cross-cutting su **B** | metrica per feature; naming/exposure | la feature relativa | chi fa la feature / **B** |

### Lo split di #4 (refund/void) — la chiave del parallelismo
- **#4a (Track B, indipendente):** nuovo endpoint idempotente `POST /api/payments/authorizations/{orderId}/void` nel **servizio Payments** (`PaymentsController.kt`, `PaymentsService.kt`, payments `application.yml`). Repo/servizio diverso → **zero overlap** col core di A. Parallelo da subito.
- **#4b (Track A, dopo #1):** chiamata di compensazione nel saga + metodo client `payments.void(...)` nel gateway (`OrderSaga.kt:58-64`, `Gateways.kt`). Tocca il core conteso → owner A.
- **Handshake:** A e B **concordano il contratto API del void** (path, body, risposta idempotente) PRIMA di iniziare, poi costruiscono in parallelo e integrano. È il pattern saga già usato (Inventory `release`).

### Due track concreti

**Track A — "Core money-path" (owner unico di `OrderSaga.kt`, `Gateways.kt`, orders `application.yml`)**
1. #1 Circuit breaker + PAYMENT_PENDING (keystone)
2. #2 Retry + Bulkhead (DOPO #1, stesso metodo/blocco yaml)
3. #4b compensazione lato saga (chiama l'endpoint di B)
4. #5 + #7 relativi (test breaker/parking/recovery; metrica `orders_payments_unavailable_total`)

**Track B — "Superfici indipendenti" (nessun file in comune con A)**
1. #3 Backpressure 429 su `OrdersController` + ratelimiter
2. #4a endpoint `void` nel servizio Payments
3. #6 review/validazione chart K8s (probe/PDB/HPA/topology) nel config-repo
4. #7 cross-cutting: convenzione naming metriche, esposizione `/actuator/prometheus`, prep dashboard Fase 4; metrica `orders_intake_shed_total`

### Cosa parte SUBITO in contemporanea
- **A → #1** mentre **B → #3 + #4a** (zero conflitti: file totalmente disgiunti).
- Poi **A → #2 → #4b** mentre **B → #6 + #7-crosscutting**.

### Punti di sincronizzazione (coordinare a voce)
1. **Contratto API `void`** (A↔B) prima di #4.
2. **Blocco `resilience4j` in orders `application.yml`:** A possiede `circuitbreaker`/`retry`/`bulkhead`/`app.recovery`, B aggiunge **solo** `ratelimiter` → sotto-blocchi distinti, merge banale ma da coordinare (stesso file).
3. **`docs/`** (`design.md`, `capstone-dod.md`, `agent-log.md`): append-only, ognuno la propria sezione.
4. **Branch corti** (Lab/DORA): feature branch < 1 giorno, merge su main verde, niente rami lunghi divergenti sul core.

> Regola pratica: **un solo sviluppatore alla volta tocca i 3 file del core.** Se entrambi devono lavorarci,
> serializzare (A finisce #1, poi B può prendere il core per un task suo) invece di mergiare a forza.
