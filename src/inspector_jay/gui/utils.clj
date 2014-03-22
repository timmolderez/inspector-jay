; Copyright (c) 2013-2014 Tim Molderez.
;
; All rights reserved. This program and the accompanying materials
; are made available under the terms of the 3-Clause BSD License
; which accompanies this distribution, and is available at
; http://www.opensource.org/licenses/BSD-3-Clause

(ns inspector-jay.gui.utils
  "Some utility functions"
  {:author "Tim Molderez"}
  (:use 
    [clojure.string :only [join]]))

(defn truncate ^String [string length]
  "Returns a truncated string. If the string is longer than length, we only return the first 'length' characters and append an ellipsis to it."
  (if (> (count string) length)
    (str (subs string 0 length) "...")
    string))

(defn to-string-sequence ^String [sequence]
  "Create a string listing the elements of a sequence"
  (let [n (count sequence)
        indexWidth (if (> n 1)
                     (int (java.lang.Math/ceil (java.lang.Math/log10 n)))
                     1)]
    (join
      (for [x (range 0 n)]
        (format (str "[%0" indexWidth "d] %s\n") x (nth sequence x))))))