; Copyright (c) 2013-2015 Tim Molderez.
;
; All rights reserved. This program and the accompanying materials
; are made available under the terms of the 3-Clause BSD License
; which accompanies this distribution, and is available at
; http://www.opensource.org/licenses/BSD-3-Clause

(ns inspector-jay.core
  "Inspector Jay is a graphical inspector that lets you examine Java/Clojure objects and data structures."
  {:author "Tim Molderez"}
  (:gen-class
    :name inspectorjay.InspectorJay
    :prefix java-
    :methods [#^{:static true} [inspect [Object] Object]])
  (:require [inspector-jay.gui
             [gui :as gui]
             [utils :as utils]]))

(defn inspect
  "Displays an inspector window for a given object.
  The return value of inspect is the object itself, so you can plug in this function anywhere you like.
  See gui/default-options for more information on all available keyword arguments."
  ^Object	[^Object object & {:as args}]
  (if (not= object nil)
    (apply gui/inspector-window object (utils/map-to-keyword-args args)))
  object)

(defn last-selected-value
  "Retrieve the value of the tree node that was last selected.
   See gui/last-selected-value for more information."
  []
  (gui/last-selected-value))

(defn java-inspect
  "Java wrapper for the inspect function.
   When using Java, you can call this function as follows:
   inspectorjay.InspectorJay.inspect(anObject);"
  [object]
  (inspect object))

(defn java-inspectorPanel
  "Java wrapper for the inspector-panel function.
   Rather than opening an inspector window, this method only returns the inspector's JPanel.
   You can use it to embed Inspector Jay in your own applications."
  [object]
  (gui/inspector-panel object))