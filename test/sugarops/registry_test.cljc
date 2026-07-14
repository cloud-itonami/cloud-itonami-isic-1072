(ns sugarops.registry-test
  (:require [clojure.test :refer [deftest is testing]]
            [sugarops.registry :as registry]))

;; ──────────────────────── Moisture Target ──────────────────────

(deftest moisture-out-of-target-test
  (testing "moisture at target with no tolerance returns false"
    (is (false? (registry/moisture-out-of-target? 0.04 0.04 0.02))))

  (testing "moisture within tolerance range returns false"
    (is (false? (registry/moisture-out-of-target? 0.05 0.04 0.02))))

  (testing "moisture below tolerance returns true (violation)"
    (is (true? (registry/moisture-out-of-target? 0.01 0.04 0.02))))

  (testing "moisture above tolerance returns true (violation)"
    (is (true? (registry/moisture-out-of-target? 0.10 0.04 0.02)))))

;; ──────────────────────── Polarization ──────────────────────

(deftest polarization-below-minimum-test
  (testing "polarization at minimum returns false (no violation)"
    (is (false? (registry/polarization-below-minimum? 99.7 99.7))))

  (testing "polarization above minimum returns false"
    (is (false? (registry/polarization-below-minimum? 99.9 99.7))))

  (testing "polarization below minimum returns true (violation)"
    (is (true? (registry/polarization-below-minimum? 95.0 99.7)))))

;; ──────────────────────── Color ──────────────────────

(deftest color-exceeds-max-test
  (testing "color within max returns false (no violation)"
    (is (false? (registry/color-exceeds-max? 30 45))))

  (testing "color at max returns false"
    (is (false? (registry/color-exceeds-max? 45 45))))

  (testing "color exceeding max returns true (violation)"
    (is (true? (registry/color-exceeds-max? 100 45)))))

;; ──────────────────────── Ash Content ──────────────────────

(deftest ash-content-exceeds-max-test
  (testing "ash content within max returns false (no violation)"
    (is (false? (registry/ash-content-exceeds-max? 0.02 0.03))))

  (testing "ash content at max returns false"
    (is (false? (registry/ash-content-exceeds-max? 0.03 0.03))))

  (testing "ash content exceeding max returns true (violation)"
    (is (true? (registry/ash-content-exceeds-max? 0.10 0.03)))))

;; ──────────────────────── SO2 Residue ──────────────────────

(deftest so2-residue-exceeds-max-test
  (testing "SO2 residue within limit returns false (no violation)"
    (is (false? (registry/so2-residue-exceeds-max? 15 20))))

  (testing "SO2 residue at limit returns false"
    (is (false? (registry/so2-residue-exceeds-max? 20 20))))

  (testing "SO2 residue exceeding limit returns true (violation)"
    (is (true? (registry/so2-residue-exceeds-max? 25 20)))))

;; ──────────────────────── Granulation ──────────────────────

(deftest granulation-out-of-range-test
  (testing "granulation within range returns false (no violation)"
    (is (false? (registry/granulation-out-of-range? 650 500 850))))

  (testing "granulation below minimum returns true (violation)"
    (is (true? (registry/granulation-out-of-range? 300 500 850))))

  (testing "granulation above maximum returns true (violation)"
    (is (true? (registry/granulation-out-of-range? 1200 500 850)))))

;; ──────────────────────── Metal Detector Calibration ──────────────────────

(deftest metal-detector-calibration-overdue-test
  (testing "recent calibration returns false (no violation)"
    ;; Assume calibrated 30 days ago
    (let [now #?(:clj (System/currentTimeMillis) :cljs (.now js/Date))
          thirty-days-ago (- now (* 30 24 60 60 1000))]
      (is (false? (registry/metal-detector-calibration-overdue? thirty-days-ago now)))))

  (testing "overdue calibration returns true (violation)"
    (let [now #?(:clj (System/currentTimeMillis) :cljs (.now js/Date))
          hundred-days-ago (- now (* 100 24 60 60 1000))]
      (is (true? (registry/metal-detector-calibration-overdue? hundred-days-ago now))))))

;; ──────────────────────── Weight Variance ──────────────────────

(deftest weight-variance-excessive-test
  (testing "variance within tolerance returns false (no violation)"
    (is (false? (registry/weight-variance-excessive? 45 50))))

  (testing "variance at tolerance returns false"
    (is (false? (registry/weight-variance-excessive? 50 50))))

  (testing "variance exceeding tolerance returns true (violation)"
    (is (true? (registry/weight-variance-excessive? 51 50)))))

;; ──────────────────────── Sulfite Labeling ──────────────────────

(deftest sulfite-label-risk-test
  (testing "SO2 below threshold returns false (no risk) regardless of declaration"
    (is (false? (registry/sulfite-label-risk? 5 10 #{}))))

  (testing "SO2 at/above threshold with sulfites declared returns false (no risk)"
    (is (false? (registry/sulfite-label-risk? 15 10 #{:sulfites}))))

  (testing "SO2 at/above threshold without sulfites declared returns true (risk)"
    (is (true? (registry/sulfite-label-risk? 15 10 #{})))))

;; ──────────────────────── Foreign Material ──────────────────────

(deftest foreign-material-detected-test
  (testing "no detection returns false"
    (is (false? (registry/foreign-material-detected? false)))
    (is (false? (registry/foreign-material-detected? nil))))

  (testing "detection returns true"
    (is (true? (registry/foreign-material-detected? true)))))

;; ──────────────────────── Sanitation Score ──────────────────────

(deftest sanitation-score-insufficient-test
  (testing "score at minimum returns false (no violation)"
    (is (false? (registry/sanitation-score-insufficient? 75 75))))

  (testing "score above minimum returns false"
    (is (false? (registry/sanitation-score-insufficient? 85 75))))

  (testing "score below minimum returns true (violation)"
    (is (true? (registry/sanitation-score-insufficient? 74 75)))))
