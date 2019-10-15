; Copyright (c) 2013-2015 Tim Molderez.
;
; All rights reserved. This program and the accompanying materials
; are made available under the terms of the 3-Clause BSD License
; which accompanies this distribution, and is available at
; http://www.opensource.org/licenses/BSD-3-Clause

(ns inspector-jay.gui.utils
  "Some utility functions"
  {:author "Tim Molderez"}
  (:require
    [clojure.string :as s]))

(defn truncate
  "Returns a truncated string. If the string is longer than length, we only return the first 'length' characters and append an ellipsis to it."
  ^String [string length]
  (if (> (count string) length)
    (str (subs string 0 length) "...")
    string))

(defn to-string-sequence
  "Create a string, listing the elements of a sequence"
  ^String [sequence]
  (let [n (count sequence)
        indexWidth (if (> n 1)
                     (int (java.lang.Math/ceil (java.lang.Math/log10 n)))
                     1)]
    (s/join
      (for [x (range 0 n)]
        (format (str "[%0" indexWidth "d] %s\n") x (nth sequence x))))))

(defn map-to-keyword-args
  "Convert a map to a list of keyword arguments.
   Used to pass in keyword arguments via clojure.core/apply"
  [args-map]
  (mapcat identity (vec args-map)))
