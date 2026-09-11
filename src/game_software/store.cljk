(ns game-software.store
  "SSoT for the JSIC 3914 ゲームソフトウェア業 (game software production)
  sole-proprietor actor. Store is a protocol injected into the
  `game-software.actor` StateGraph — `MemStore` is the default,
  deterministic, zero-dep backend; a Datomic/kotoba-server-backed
  implementation can be swapped in without touching the actor or governor
  (itonami actor pattern, per ADR-2607011000 / CLAUDE.md Actors section).

  Domain:

    title   — a registered game title (:title/id, plus the two clearances a
              game needs before it may ship: :age-rated? and
              :assets-cleared?). A game's rights surface is not a film's:
              what has to be cleared is the **age rating** (CERO/IARC) and
              the licence of every third-party or generated asset in the
              build — engine, font, music bed, sound cue, sprite.
    record  — a committed operating record under a title (build, publish) —
              written ONLY via commit-record!, never mutated in place
    ledger  — an append-only audit trail of every proposal/verdict/
              disposition, regardless of outcome (commit or hold)")

(defprotocol Store
  (title [s title-id])
  (records-of [s title-id])
  (ledger [s])
  (register-title! [s title])
  (commit-record! [s record])
  (append-ledger! [s fact]))

(defrecord MemStore [a]
  Store
  (title [_ title-id] (get-in @a [:titles title-id]))
  (records-of [_ title-id]
    (filter #(= title-id (:title-id %)) (:records @a)))
  (ledger [_] (:ledger @a))
  (register-title! [s t]
    (swap! a assoc-in [:titles (:title/id t)] t) s)
  (commit-record! [s record]
    (swap! a update :records (fnil conj []) record) s)
  (append-ledger! [s fact]
    (swap! a update :ledger (fnil conj []) fact) s))

(defn mem-store
  ([] (mem-store {}))
  ([seed] (->MemStore (atom (merge {:titles {} :records [] :ledger []} seed)))))
