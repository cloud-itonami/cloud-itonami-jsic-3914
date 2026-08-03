# cloud-itonami-jsic-3914

Open Industry Blueprint for **JSIC 3914**: ゲームソフトウェア業 (Game Software
Production) — published as an OSS business that any qualified operator can
fork, deploy, run, improve and sell.

A studio takes a game design expressed as data (a **gameSpec**), builds it into
a playable title, and ships it. Both of those steps get proposed by a GameOps
advisor and then have to survive an independent **GameProductionGovernor**
before anything is committed.

> **Why an actor layer at all?** A game-design LLM is good at proposing the
> next build and drafting a release note — but it has **no notion of whether
> the design it is building holds together**. A weapon whose `:weapon/evolves-to`
> names a weapon that was renamed does not fail: it loads, it builds, and it
> simply never evolves. A boss whose phase thresholds do not descend to zero
> cannot be killed in its final phase. A spec whose best weapon cannot sustain
> the damage the terminal spawn rate demands produces a game where *every* run
> ends by attrition no matter how the player moves — invisible in a screenshot,
> in a unit test, and in a thirty-second playtest. Letting the advisor commit
> directly ships all three. This project seals the advisor into a single node
> and wraps it with an independent governor, a human **review workflow**, and an
> immutable **audit ledger**.

## Scope (deliberately narrow — read this before anything else)

This actor governs **build and publish decisions for a registered game title**.
It never renders assets, never runs a game, and never scores whether a game is
*fun*. Design consistency and balance arithmetic come from the
[`kotoba-lang/game-production`](https://github.com/kotoba-lang/game-production)
craft library — this repo does not own that truth and does not reimplement it.
Production itself (prompts, catalog, generation fleet, publishing keys) lives in
the business layer, [`cloud-itonami/gameka`](https://github.com/cloud-itonami/gameka).

That three-way split is ADR-2607023000's rule for every creative `-ka`:

| layer | where | this game's |
|---|---|---|
| 技芸 craft | kotoba-lang, public | `kotoba-lang/game-production` |
| 職能 occupation | cloud-itonami, public AGPL | **this repo** |
| 商売 business | cloud-itonami, private | `cloud-itonami/gameka` |

## The classification, and why it is JSIC

- 大分類 **G 情報通信業**
- 中分類 **39 情報サービス業**
- 小分類 **391 ソフトウェア業**
- 細分類 **3914 ゲームソフトウェア業**

Sourced from e-stat, both revisions checked:
https://www.e-stat.go.jp/classifications/terms/10/03/3914 (2013-10) and
https://www.e-stat.go.jp/classifications/terms/10/04/3914 (current 2023-07).
The definition is *"家庭用テレビゲーム機、携帯用電子ゲーム機、パーソナル
コンピュータ等で用いるゲームソフトウェアの作成"* — creation of the software
itself, explicitly **not** cartridge or optical-disc manufacturing (those are
3296).

JSIC is used here as the complementary axis, under the narrow rule
`cloud-itonami-jsic-4721` established and `cloud-itonami-jsic-4113` followed:
**only when ISIC/ISCO cannot express the occupation without colliding with an
implemented, unrelated business.** All three candidates collide:

| candidate | occupied by |
|---|---|
| ISCO-08 2166 Graphic and Multimedia Designers | `cloud-itonami-isco-2166` — an *Independent Graphic Design Studio* (print proofing) |
| ISCO-08 2512 / 2513 | `cloud-itonami-isco-2512` *Independent Software Development Studio*, `-2513` *Community Web Studio* — generic, no game surface |
| ISIC Rev.4 5820 Software publishing | `cloud-itonami-isic-5820` — a CRM / subscription-commerce SaaS actor |

This is the same collision animeka hit (ADR-2607221000), for the same reason:
the general code was claimed first by a general business.

### Numeric-collision check ("does '3914' mean something else elsewhere?")

Run before adopting the code, following the precedent set by `jsic-4721`:

- **ISIC Rev.4** — division 39 (*Remediation activities and other waste
  management services*) contains a single group, **390**, and therefore no
  class can begin `391`. The workspace's own ISIC registry agrees: it holds
  `cloud-itonami-isic-3900` and nothing else in that division. **No ISIC class
  3914 exists.** No collision.
- **ISCO-08** — major group 3 stops at sub-major group 35; there is no 39, so
  no unit group 3914. Checked against the workspace's 341 `cloud-itonami-isco-*`
  repos, whose group-3 codes run 3111–35xx with nothing in 39xx.
  ⚠ Method note: ILO's own ISCO-08 structure page returned HTTP 403 at the time
  of writing, so this arm rests on the workspace registry rather than on the
  primary source — weaker evidence than the ISIC arm, and recorded as such
  rather than reported as if the primary source had been read.

**Conclusion: no live numeric collision found** for JSIC 3914.

## The graph

```text
:intake -> :advise -> :govern -> :decide -+-> :commit           (:ok? true)
                                          +-> :request-approval (:escalate? true, interrupt-before)
                                          +-> :hold             (:hard? true)
```

The unconditional invariant: **the advisor can never commit a record the
governor refuses.** Every `commit-record!` is gated behind `:decide`, and a
build commit carries the design report the governor actually computed — so what
was approved is exactly what was built.

### What the governor holds on

**HARD** (never overridable, no write):

1. `:no-title` — the title is not registered.
2. `:no-actuation` — the proposal's `:effect` is not `:propose`.

**ESCALATION** (human sign-off):

3. `:rights-clearance` — `:publish` without both an age rating (CERO/IARC) and
   asset-licence clearance. A game's rights surface is not a film's: what has
   to clear is the rating plus the licence of every third-party or generated
   asset in the build — engine, font, music bed, sound cue, sprite.
4. `:inconsistent-gamespec` — `game-production.spec/problems` returned
   findings. All of them are `nil` lookups at runtime.
5. `:no-endgame` — no weapon can sustain the dps
   `game-production.balance/clear-rate-required-milli` demands at the terminal
   spawn rate, against the most-spawned enemy. The verdict carries the two
   numbers so the hold says *by how much*.
6. `:degraded-generation` — `:publish` of a build whose producer reported a
   generation leg that fell back to `:placeholder` or `:silent`. A silent game,
   or one whose weapons have no cue, is not a release. (Same leg vocabulary as
   ADR-2800002700.)
7. `:low-confidence` — below `confidence-floor` (0.6).

Note what rule 5 is *not*: it is not a judgement that the game is bad. It is
the one design property that makes a run structurally unwinnable, computed
arithmetically from numbers the spec states outright. Everything softer than
that is left to humans on purpose.

## Run

```bash
clojure -M:test
clojure -M:lint
```

See `docs/operator-guide.md` to run one, and `docs/business-model.md` for what
this business is.

AGPL-3.0-or-later.
