; Copyright (c) 2013 Tim Molderez.
;
; All rights reserved. This program and the accompanying materials
; are made available under the terms of the 3-Clause BSD License
; which accompanies this distribution, and is available at
; http://www.opensource.org/licenses/BSD-3-Clause

(ns inspector-jay.tree-model
  "Defines the tree model of the object inspector"
  {:author "Tim Molderez"}
  (:use 
    [inspector-jay.tree-node])
  (:import
     [java.util Collection Map RandomAccess]
     [javax.swing.tree TreeModel]
     [java.lang.reflect Method]))

(defmulti is-leaf
  "Is this tree node a leaf?"
  (fn [node] (-> node .getCollectionKind)))
(defmulti get-child
  "Get the child of a node at a certain index"
  (fn [node index] (-> node .getCollectionKind)))
(defmulti get-child-count
  "Get the number of children of a node"
  (fn [node] (-> node .getCollectionKind)))

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

(defmethod get-child :default [node index]
  (if (< index (-> node .countMethods))
    (let
      [meth (nth (-> node .getMethods) index)]
      (method-node meth (-> node .getValue)))
    (let
      [field-index (- index (-> node .countMethods))
       field (nth (-> node .getFields) field-index)]
      (field-node field (-> node .getValue)))))
(defmethod get-child :sequence [node index]
  (object-node (nth (-> node .getValue) index)))
(defmethod get-child :collection [node index]
  (object-node (nth (seq (-> node .getValue)) index)))

(defmethod get-child-count :default [node]
  (if (-> node .hasValue)
    (+ (-> node .countMethods) (-> node .countFields))
    0))
(defmethod get-child-count :sequence [node]
  (if (-> node .hasValue)
    (count (-> node .getValue))
    0))
(defmethod get-child-count :collection [node]
  (if (-> node .hasValue)
    (count (-> node .getValue))
    0))

(defn tree-model ^TreeModel
  [^Object rootObj]
  "Define a tree model around rootObj, which is the object we want to inspect"
  (proxy [TreeModel] []
    (getRoot [] (object-node rootObj))
    (addTreeModelListener [treeModelListener])
    (getChild [parent index]
      (get-child parent index))
    (getChildCount [parent]
       (get-child-count parent))
    (isLeaf [node]
      (is-leaf node))
    (valueForPathChanged [path newValue])
    (getIndexOfChild [parent child] -1)
    (removeTreeModelListener [treeModelListener])))