; Copyright (c) 2013-2015 Tim Molderez.
;
; All rights reserved. This program and the accompanying materials
; are made available under the terms of the 3-Clause BSD License
; which accompanies this distribution, and is available at
; http://www.opensource.org/licenses/BSD-3-Clause

(ns inspector-jay.core-test
  "Inspector Jay unit tests"
  (:use clojure.test
        inspector-jay.core))

(deftest a-test
  (testing "FIXME, I fail."
    (is (= 0 1))))

(comment
  ; Open a ton of inspectors sequentially
  (doseq [x (range 1 40)]
    (do (inspect x) (println x)))

  ; Simultaneously open many inspectors from different threads
  (doseq [x (range 1 80)]
    (future (do
              (inspect x :pause true)
              (println x))))

  (run-tests 'inspector-jay.core-test))
