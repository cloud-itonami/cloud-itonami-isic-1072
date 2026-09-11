(ns sugarops.phase-test
  (:require [clojure.test :refer [deftest is testing]]
            [sugarops.phase :as phase]))

;; ──────────────────────── Phase Validity ──────────────────────

(deftest valid-phase-test
  (testing "intake is valid"
    (is (true? (phase/valid-phase? :intake))))

  (testing "crystallization is valid"
    (is (true? (phase/valid-phase? :crystallization))))

  (testing "archived is valid"
    (is (true? (phase/valid-phase? :archived))))

  (testing "invalid phase returns false"
    (is (false? (phase/valid-phase? :invalid)))))

;; ──────────────────────── Phase Transitions ──────────────────────

(deftest can-transition-test
  (testing "intake -> extraction is valid (forward progression)"
    (is (true? (phase/can-transition? :intake :extraction))))

  (testing "intake -> crystallization is valid (skip extraction/clarification)"
    (is (true? (phase/can-transition? :intake :crystallization))))

  (testing "extraction -> intake is invalid (backward)"
    (is (false? (phase/can-transition? :extraction :intake))))

  (testing "crystallization -> archived is valid (forward to end)"
    (is (true? (phase/can-transition? :crystallization :archived))))

  (testing "archived -> intake is invalid (backward from end)"
    (is (false? (phase/can-transition? :archived :intake))))

  (testing "same phase is invalid"
    (is (false? (phase/can-transition? :crystallization :crystallization))))

  (testing "invalid phases return false"
    (is (false? (phase/can-transition? :invalid :crystallization)))
    (is (false? (phase/can-transition? :crystallization :invalid)))))
