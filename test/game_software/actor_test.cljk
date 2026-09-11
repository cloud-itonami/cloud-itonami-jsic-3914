(ns game-software.actor-test
  (:require [clojure.test :refer [deftest is testing]]
            [game-software.actor :as actor]
            [game-software.governor-test :refer [holdable-spec unholdable-spec]]
            [game-software.store :as store]))

(defn- fixture []
  (let [s (store/register-title!
           (store/mem-store)
           {:title/id "t1" :age-rated? true :assets-cleared? true})]
    {:store s :graph (actor/build-graph {:store s})}))

(deftest a-sound-build-runs-through-to-commit
  (let [{:keys [store graph]} (fixture)
        out (actor/run-request! graph {:title-id "t1" :op :build :spec holdable-spec}
                                {} "thread-sound")]
    (is (= :done (:status out)))
    (is (= :commit (get-in out [:state :disposition])))
    (is (= 1 (count (store/records-of store "t1"))))
    (testing "the committed record carries the design the governor actually checked"
      (let [r (first (store/records-of store "t1"))]
        (is (= 0 (:problem-count (:design r))))
        (is (true? (:holdable? (:endgame (:design r)))))))
    (testing "the ledger records the commit"
      (is (= [:commit] (mapv :disposition (store/ledger store)))))))

(deftest a-game-with-no-endgame-interrupts-before-committing
  (let [{:keys [store graph]} (fixture)
        out (actor/run-request! graph {:title-id "t1" :op :build :spec unholdable-spec}
                                {} "thread-noendgame")]
    (is (= :interrupted (:status out)))
    (testing "nothing was written while waiting for the human"
      (is (empty? (store/records-of store "t1"))))
    (testing "resuming is the human decision, and it commits"
      (let [resumed (actor/approve! graph "thread-noendgame")]
        (is (= :done (:status resumed)))
        (is (= 1 (count (store/records-of store "t1"))))
        (testing "the record still carries the design that was held on, unchanged"
          (is (false? (:holdable? (:endgame (:design (first (store/records-of store "t1"))))))))))))

(deftest an-unregistered-title-holds-and-writes-nothing
  (let [{:keys [store graph]} (fixture)
        out (actor/run-request! graph {:title-id "ghost" :op :build :spec holdable-spec}
                                {} "thread-ghost")]
    (is (= :done (:status out)))
    (is (= :hold (get-in out [:state :disposition])))
    (is (nil? (get-in out [:state :record])))
    (is (empty? (store/records-of store "ghost")))
    (testing "a hold is still audited — the refusal is evidence too"
      (is (= [:hold] (mapv :disposition (store/ledger store)))))))

(deftest publishing-a-degraded-build-does-not-slip-through
  (let [{:keys [store graph]} (fixture)
        out (actor/run-request! graph {:title-id "t1" :op :publish
                                       :legs {:bed :silent :sfx :murakumo}}
                                {} "thread-degraded")]
    (is (= :interrupted (:status out)))
    (is (empty? (store/records-of store "t1")))))

(deftest publishing-a-clean-build-needs-no-human
  (let [{:keys [store graph]} (fixture)
        out (actor/run-request! graph {:title-id "t1" :op :publish
                                       :legs {:bed :murakumo :sfx :murakumo}}
                                {} "thread-clean-publish")]
    (is (= :done (:status out)))
    (is (= :publish (:op (first (store/records-of store "t1")))))))
