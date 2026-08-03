(ns game-software.governor
  "GameProductionGovernor — the independent design-integrity / rights layer
  for the JSIC 3914 ゲームソフトウェア業 (game software production) actor.
  Wired as its own `:govern` node in `game-software.actor`'s StateGraph,
  downstream of `:advise` — the Advisor proposes builds and releases and has
  no notion of whether the design it is building is internally consistent or
  whether the title may legally ship, so this MUST be a separate system able
  to reject a proposal (itonami actor pattern, per ADR-2607011000 /
  CLAUDE.md Actors section).

  The design checks are NOT bespoke. They call the kotoba-lang
  `game-production` craft lib (ADR-2607023000) against the request's actual
  gameSpec:

  - `game-production.spec/problems` — dangling `:weapon/evolves-to`,
    `:weapon/merge-passive`, `:move/summon-id` and coop-synergy references,
    duplicate ids, boss phases that never reach zero, overlapping
    night-rage windows, an inverted spawn-interval floor. Every one of them
    is a `nil` lookup at runtime: the build succeeds and the game is broken
    five minutes into play. A build proposal carrying any of them goes to a
    human.

  - `game-production.balance/clear-rate-required-milli` vs the best weapon's
    crit-adjusted dps — `holdable?` below. If no weapon in the spec can
    sustain the dps needed to stop the alive count growing at the terminal
    spawn rate, the field fills to `:waves/max-alive` and *every* run ends
    by attrition regardless of how the player moves. That is a game with no
    endgame, and it is invisible in a screenshot, in a unit test, and in a
    thirty-second playtest.

  `check` is a pure function of (request, context, proposal, store) ->
  verdict; it never mutates the store. The StateGraph's `:decide` node
  routes on the verdict:
    :hard? true                → :hold  (irreversible, no write)
    :escalate? true            → :request-approval (interrupt-before)
    otherwise                  → :commit

  HARD invariants (:hard? true, ALWAYS :hold, never overridable):
    1. title provenance — the request's title must be registered.
    2. no-actuation    — proposal :effect must be :propose.
  ESCALATION invariants (:escalate? true, ALWAYS human sign-off):
    3. :publish without an age rating AND asset-licence clearance.
    4. :build whose gameSpec has consistency problems.
    5. :build whose gameSpec has no endgame (not `holdable?`).
    6. :publish of a build that reported degraded generation legs — a
       silent game, or one whose weapons have no cue, is not a release.
    7. low confidence (< `confidence-floor`)."
  (:require [game-production.balance :as balance]
            [game-production.spec :as gamespec]
            [game-software.store :as store]))

(def confidence-floor 0.6)

(defn holdable?
  "Can any single weapon in this spec hold the terminal spawn rate?

  Compares the best crit-adjusted dps against the sustained dps needed to
  keep the alive count flat, using the most common enemy — the one with the
  highest `:enemy/spawn-weight`, since that is what the terminal rate is
  actually made of. Returns a map rather than a boolean so a hold can say
  *by how much*: `{:holdable? false :best-dps-milli .. :required-dps-milli ..
  :enemy ..}`.

  nil when the spec states no waves or no enemies — an unanswerable question
  is not the same as a failing one, and reporting `false` for a spec that
  simply has not declared its pressure yet would block work that is fine."
  [spec]
  (let [enemies (balance/enemy-table spec)
        common (->> enemies (sort-by #(- (or (:spawn-weight %) 0))) first)
        required (when-let [id (:id common)] (balance/clear-rate-required-milli spec id))
        best (or (:crit-dps-milli (first (balance/weapon-table spec))) 0)]
    (when (and common required (pos? (:required-dps-milli required)))
      {:enemy (:id common)
       :best-dps-milli best
       :required-dps-milli (:required-dps-milli required)
       :night-rage-required-dps-milli (:night-rage-required-dps-milli required)
       :holdable? (>= best (:required-dps-milli required))})))

(defn build-design-report
  "The design facts a build commit carries with it, so what was approved is
  exactly what was built: the spec's problems, its pressure envelope, and
  whether it is holdable."
  [spec]
  (let [problems (gamespec/problems spec)]
    {:spec-id (or (:gamespec/id spec) (get spec "gamespec/id"))
     :problems problems
     :problem-count (count problems)
     :pressure (balance/pressure-bounds spec)
     :endgame (holdable? spec)}))

(defn degraded-legs
  "Generation legs a producer reported as fallen back. The shape matches the
  `:legs` map the -ka producers already emit (ADR-2800002700): a leg whose
  value is `:placeholder` or `:silent` did not actually run.

  A build with no `:legs` at all reports nothing degraded rather than
  guessing — absence of a report is not evidence of a clean run, and this
  namespace says so in `build-design-report`'s sibling escalation instead of
  inventing a verdict here."
  [legs]
  (vec (for [[k v] legs
             :when (contains? #{:placeholder :silent} v)]
         {:leg k :fell-back-to v})))

(defn- hard-violations [{:keys [request proposal]} title-record]
  (cond-> []
    (nil? title-record)
    (conj {:rule :no-title
           :detail (str "未登録 title " (:title-id request))})

    (not= :propose (:effect proposal))
    (conj {:rule :no-actuation :detail "effect は :propose のみ許可（直接書込禁止）"})))

(defn check
  "Assess a proposal against `request`/`context`/`proposal` and a `store`
  implementing `game-software.store/Store`. Returns
  `{:ok? bool :violations [...] :confidence n :hard? bool :escalate? bool
    :escalations [...] :design ...}`."
  [request _context proposal store]
  (let [title-record (store/title store (:title-id request))
        hard (hard-violations {:request request :proposal proposal} title-record)
        hard? (boolean (seq hard))
        conf (or (:confidence proposal) 0.0)
        low? (< conf confidence-floor)
        clearance? (and (= :publish (:op proposal))
                        title-record
                        (not (and (:age-rated? title-record)
                                  (:assets-cleared? title-record))))
        design (when (and (= :build (:op proposal)) (:spec request))
                 (build-design-report (:spec request)))
        broken? (and design (pos? (:problem-count design)))
        unholdable? (and design
                         (some? (:endgame design))
                         (false? (:holdable? (:endgame design))))
        degraded (when (= :publish (:op proposal)) (degraded-legs (:legs request)))
        escalations (cond-> []
                      clearance? (conj {:rule :rights-clearance
                                        :detail "公開には年齢レーティング（CERO/IARC）と、build に含まれる全アセット（エンジン・フォント・BGM・SFX・スプライト）のライセンス確認が必要"})
                      broken? (conj {:rule :inconsistent-gamespec
                                     :detail (str "gameSpec に " (:problem-count design)
                                                  " 件の不整合（実行時は nil 参照として黙って壊れる）")
                                     :problems (:problems design)})
                      unholdable? (conj {:rule :no-endgame
                                         :detail "終盤の spawn rate を支えられる武器が無い（どう動いても attrition で終わる）"
                                         :endgame (:endgame design)})
                      (seq degraded) (conj {:rule :degraded-generation
                                            :detail "生成 leg が fallback に落ちた build は release ではない"
                                            :legs degraded})
                      low? (conj {:rule :low-confidence :detail conf}))]
    {:ok? (and (not hard?) (empty? escalations))
     :violations hard
     :escalations escalations
     :design design
     :confidence conf
     :hard? hard?
     :escalate? (and (not hard?) (boolean (seq escalations)))}))
