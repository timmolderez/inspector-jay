(ns inspector-jay.core-test
  (:use clojure.test
        inspector-jay.core))

(deftest a-test
  (testing "FIXME, I fail."
    (is (= 0 1))))

;(doseq [x (range 1 24)] (inspect x))

(comment
  (run-tests 'inspector-jay.core-test))