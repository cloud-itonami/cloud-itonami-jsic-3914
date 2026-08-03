# Operator Guide

## First Deployment

1. Register each title you operate (`store/register-title!`) with its two
   clearance flags: `:age-rated?` and `:assets-cleared?`. Both default to
   absent, which reads as uncleared — a title cannot publish by omission.
2. Define what asset-licence clearance means for your studio: engine terms,
   fonts, music beds, sound cues, sprites, and the licence of anything a
   generation fleet produced for you.
3. Run synthetic operating cases (see below) before wiring a real advisor.
4. Wire an advisor. `mock-advisor` is deterministic and is what CI uses;
   `llm-advisor` takes a chat model **as an argument** — resolve the fleet's
   current model through the `murakumo-main` alias rather than pinning an id
   (ADR-2607173100).
5. Keep the ledger. Every verdict is appended whether it committed or held; a
   studio that only records its successes cannot show a regulator, a publisher
   or itself what it refused.

## Running one request

```clojure
(require '[game-software.actor :as actor]
         '[game-software.store :as store])

(def st (store/register-title! (store/mem-store)
                               {:title/id "t1" :age-rated? true :assets-cleared? true}))
(def graph (actor/build-graph {:store st}))

;; build — the spec travels with the request, and the governor checks it
(actor/run-request! graph {:title-id "t1" :op :build :spec my-spec} {} "thread-1")
;; => {:status :done ...}         when the spec is consistent and holdable
;; => {:status :interrupted ...}  otherwise; nothing is written

;; a human decides, and the resume commits
(actor/approve! graph "thread-1")
```

## Reading a hold

Every escalation carries the facts, not a summary:

- `:inconsistent-gamespec` carries `:problems` — each with its `:kind` and the
  id that does not resolve. Fix the spec; do not approve past it unless the
  reference is intentionally dangling.
- `:no-endgame` carries `:endgame` with `:best-dps-milli` and
  `:required-dps-milli` in milli-units (divide by 1000 for dps). Approving past
  it means shipping a game where every run ends by attrition — sometimes that
  is the design (an endless mode), which is exactly why it is a human decision
  and not a hard invariant.
- `:degraded-generation` carries the legs that fell back. Re-run the production
  with a reachable fleet rather than approving.

## Minimum Production Controls

- title, rating and licence-clearance log
- design-hold escalation path with a named human
- provenance for all operating records (a build record carries the design
  report the governor computed, not a re-derivation)
- audit export for all gated actions

## Certification

Certified operators must prove that the governor gates every publish and every
build, that a hold writes nothing, and that both holds and commits reach the
ledger.
