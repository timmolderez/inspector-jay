; Copyright (c) 2013-2015 Tim Molderez.
;
; All rights reserved. This program and the accompanying materials
; are made available under the terms of the 3-Clause BSD License
; which accompanies this distribution, and is available at
; http://www.opensource.org/licenses/BSD-3-Clause

(ns inspector-jay.model.tree-model
  "Defines the tree model of the object inspector"
  {:author "Tim Molderez"}
  (:require
    [inspector-jay.model.tree-node :as node])
  (:import
     [java.util Collection Map RandomAccess]
     [javax.swing.tree TreeModel]
     [javax.swing.event TreeModelEvent]
     [java.lang.reflect Method]))

(defmulti is-leaf
  "Is this tree node a leaf?"
  (fn [node] (-> node .getCollectionKind)))
(defmulti get-child
  "Get the child of a node at a certain index"
  (fn [node index opts] (-> node .getCollectionKind)))
(defmulti get-child-count
  "Get the number of children of a node"
  (fn [node opts] (-> node .getCollectionKind)))

(defmethod is-leaf :default [node]
  (not (-> node .mightHaveValue)))
(defmethod is-leaf :sequence [node]
  (if (-> node .hasValue)
    (= 0 (count (-> node .getValue)))
    false))
(defmethod is-leaf :collection [node]
  (if (-> node .hasValue)
    (= 0 (count (-> node .getValue)))
    false))

(defmethod get-child :default [node index opts]
  (if (opts :methods)
    (if (< index (count (-> node (.getMethods opts))))
      ; Methods always come first; if the index does not go beyond the number of methods, we're retrieving a method
      (let [meth (nth (-> node (.getMethods opts)) index)] (node/method-node meth (-> node .getValue)))
      ; Otherwise, we must be retrieving a field
      (let [field-index (- index (count (-> node (.getMethods opts))))
            field (nth (-> node (.getFields opts)) field-index)] 
        (node/field-node field (-> node .getValue))))
    ; If methods are hidden, we must be retrieving a field
    (let [field (nth (-> node (.getFields opts)) index)] (node/field-node field (-> node .getValue)))))
(defmethod get-child :sequence [node index opts]
  (node/object-node (nth (-> node .getValue) index)))
(defmethod get-child :collection [node index opts]
  (node/object-node (nth (seq (-> node .getValue)) index)))

(defmethod get-child-count :default [node opts]
  (if (-> node .hasValue)
    (+ (if (opts :methods) (count (-> node (.getMethods opts))) 0)
      (if (opts :fields) (count (-> node (.getFields opts))) 0))
    0))
(defmethod get-child-count :sequence [node opts]
  (if (-> node .hasValue)
    (count (-> node .getValue))
    0))
(defmethod get-child-count :collection [node opts]
  (if (-> node .hasValue)
    (count (-> node .getValue))
    0))

(defn tree-model
  "Define a tree model around root, which is the object we want to inspect"
  ^TreeModel [^Object root filter-options]
  (let [listeners (new java.util.Vector)] 
    (proxy [TreeModel] []
      (getRoot [] (node/object-node root))
      (addTreeModelListener [treeModelListener]
        (-> listeners (.add treeModelListener)))
      (getChild [parent index]
        (get-child parent index filter-options))
      (getChildCount [parent]
        (get-child-count parent filter-options))
      (isLeaf [node]
        (is-leaf node))
      (valueForPathChanged [path newValue]
        (let [e (new TreeModelEvent newValue path)]
          (doseq [listener listeners]
            (-> listener (.treeStructureChanged e)))))
      (getIndexOfChild [parent child] -1)
      (removeTreeModelListener [treeModelListener]
        (-> listeners (.remove treeModelListener))))))