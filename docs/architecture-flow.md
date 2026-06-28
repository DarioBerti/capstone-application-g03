# EuroTransit — application flow

Two views of how a purchase moves through the system. The **sequence diagram** is the money path
(place-order saga); the **topology diagram** shows who talks to whom and which store each service owns.

> Source of truth is the code — if the controllers/listeners change, update these diagrams.

## Place-order saga (sequence)

Orders accepts the request **synchronously with `202`**, persisting `Order(PENDING)` plus an
**outbox** `order-placed` row in one transaction, then **consumes its own `order-placed`** to run the
saga. The branch decisions come from **synchronous REST** calls to Inventory and Payments; the
`inventory-*` / `payment-authorized` events are best-effort observability. Notifications consumes only
`notification-requested` and is never on the money path.

```mermaid
sequenceDiagram
    autonumber
    actor U as Client / Debug UI
    participant O as Orders :8080
    participant K as Kafka (Strimzi)
    participant I as Inventory :8081
    participant P as Payments :8082
    participant N as Notifications :8084

    U->>O: POST /api/orders {productId, qty}
    Note over O: tx: save Order(PENDING) + outbox(order-placed)
    O-->>U: 202 Accepted {orderId, PENDING}
    O->>K: outbox relay publishes order-placed
    K->>O: order-placed (listen-to-yourself)

    activate O
    O->>I: POST /reservations (sync)
    alt seats available
        I-->>O: 200 RESERVED
        I--)K: inventory-reserved (best-effort)
        O->>P: POST /authorizations (sync)
        alt authorized
            P-->>O: 200 AUTHORIZED
            P--)K: payment-authorized (best-effort)
            O->>I: POST .../confirm (sync)
            I-->>O: 200 CONFIRMED
            Note over O: Order -> CONFIRMED
            O->>K: order-confirmed + notification-requested
        else declined
            P-->>O: 200 DECLINED
            O->>I: POST .../release (compensate)
            I--)K: inventory-released (best-effort)
            Note over O: Order -> REJECTED (PAYMENT_DECLINED)
            O->>K: order-rejected + notification-requested
        end
    else sold out
        I-->>O: 200 REJECTED
        Note over O: Order -> REJECTED (SOLD_OUT)
        O->>K: order-rejected + notification-requested
    end
    deactivate O

    K->>N: notification-requested
    Note over N: send confirmation (stateless, off money path)
```

## Service & event topology

Solid arrows are **synchronous REST**; dotted arrows are **asynchronous Kafka** events. Each service
owns its own database (database-per-service); only Orders keeps an outbox.

```mermaid
flowchart LR
    client([Client / Debug UI])

    client -->|/api/catalog| C["Catalog :8083<br/>(off money path)"]
    client -->|/api/orders| O["Orders :8080<br/>(saga orchestrator)"]

    O -->|sync REST| I["Inventory :8081"]
    O -->|sync REST| P["Payments :8082"]

    C --- CDB[("catalogdb")]
    O --- ODB[("ordersdb<br/>+ outbox")]
    I --- IDB[("inventorydb")]
    P --- PDB[("paymentsdb")]

    K{{"Kafka (Strimzi)"}}
    O -. "order-placed / -confirmed / -rejected" .-> K
    O -. "notification-requested" .-> K
    I -. "inventory-reserved / -released" .-> K
    P -. "payment-authorized" .-> K
    K -. "order-placed (self)" .-> O
    K -. "notification-requested" .-> N["Notifications :8084<br/>(stateless sink)"]

    classDef money fill:#15324a,stroke:#4f8cff,color:#e6e8ec;
    classDef edge fill:#1d3a24,stroke:#2faa5a,color:#e6e8ec;
    class O,I,P money;
    class C,N edge;
```

## Why it's shaped this way

- **202 + async saga** — the caller gets a durable acknowledgement immediately; reservation/payment/
  confirmation proceed off the request thread.
- **Transactional outbox + listen-to-yourself** — the business row and the `order-placed` event commit
  together, so an event never escapes for a rolled-back change and a committed change never loses its event.
- **Synchronous decisions on the money path** — reserve and authorize must be answered *now*, so they're
  REST calls whose responses drive the saga branch; `SOLD_OUT` / `DECLINED` are `200` business outcomes,
  not HTTP errors (an error would trip the caller's retries/circuit breaker).
- **Compensation** — a decline after a successful reservation triggers a `release` so seats aren't leaked.
- **Best-effort events vs. load-bearing responses** — `inventory-*` / `payment-authorized` are published
  after commit for observability; correctness never depends on their delivery.
- **Notifications off the money path** — checkout succeeds even if notifications are entirely down.
