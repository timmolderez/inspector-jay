; Copyright (c) 2013 Tim Molderez.
;
; All rights reserved. This program and the accompanying materials
; are made available under the terms of the 3-Clause BSD License
; which accompanies this distribution, and is available at
; http://www.opensource.org/licenses/BSD-3-Clause

(ns inspector-jay.core
  "Inspector Jay is a simple GUI application that lets you inspect Java objects and Clojure data structures."
  {:author "Tim Molderez"}
  (:gen-class
    :name inspectorjay.InspectorJay
    :prefix java-
    :methods [#^{:static true} [inspect [Object] Object]])
  (:use
    [clojure.java.io :only [resource]]
    [seesaw
     [core]
     [border]
     [font]]
    [inspector-jay
     [tree-node :only [object-node]]
     [tree-model]
     [gui]]))

; Use the OS's native look and feel
(native!)

; Inspector GUI options
(def gui-options
  {:width 800,
   :height 600
   :font (font :name :sans-serif :style #{:plain})})

(defn 
	inspect ^Object
	[^Object object]
 "Displays an object inspector window for a given object.
  The return value of inspect is the object itself, so you can plug in this function anywhere you like."
	(let [f (frame :title (str "Object inspector : " (.toString object)) 
                :size [(gui-options :width) :by (gui-options :height)]
                :on-close :dispose)
       obj-info (text :multi-line? true :editable? false :text (to-string-verbose (object-node object)) 
                  :font (gui-options :font))
       obj-tree (tree :model (tree-model object))
       crumbs (label :text (to-string-breadcrumb (object-node object)) :icon (icon (resource "icons/toggle_breadcrumb.gif")))
       obj-info-scroll (scrollable obj-info)
       obj-tree-scroll (scrollable obj-tree)
       split-pane (top-bottom-split obj-info-scroll obj-tree-scroll :divider-location 1/5)
       main-panel (border-panel :north (tool-panel obj-tree) :south crumbs :center split-pane)]
   (-> split-pane (.setDividerSize 9))
   (-> obj-info-scroll (.setBorder (empty-border)))
   (-> obj-tree-scroll (.setBorder (empty-border)))
   (-> obj-tree (.setCellRenderer (tree-renderer)))
   (-> obj-tree (.addTreeSelectionListener (tree-selection-listener obj-info crumbs)))
   (-> obj-tree (.addTreeExpansionListener (tree-expansion-listener obj-info)))
   (-> obj-tree (.addTreeWillExpandListener (tree-will-expand-listener)))
   (bind-keys f obj-tree)
   (config! f :content main-panel)
   (-> f show!)
   (-> obj-tree .requestFocus)
   object))

(defn
  java-inspect [object]
  "Java wrapper for the inspect function.
   When using Java, you can call this function as follows:
   inspectorjay.InspectorJay.inspect(anObject);"
  (inspect object))