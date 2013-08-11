; Copyright (c) 2013 Tim Molderez.
;
; All rights reserved. This program and the accompanying materials
; are made available under the terms of the 3-Clause BSD License
; which accompanies this distribution, and is available at
; http://www.opensource.org/licenses/BSD-3-Clause

(ns inspector-jay.tree-ui
  "Defines how each tree node is displayed in the GUI"
  {:author "Tim Molderez"}
  (:use 
    [clojure.string :only [join]]
    [clojure.java.io :only [resource]]
    [clojure.java.javadoc]
    [seesaw.core]
    [inspector-jay.tree-node])
  (:import
     [javax.swing JTextArea KeyStroke JFrame JTree JComponent]
     [javax.swing.tree DefaultTreeCellRenderer]
     [javax.swing.event TreeSelectionListener]
     [java.awt.event KeyEvent ActionListener]
     [java.lang.reflect Modifier]))

(defmulti to-string
  "Retrieve a short description of a tree node"
  (fn [node] (-> node .getKind)))
(defmulti to-string-breadcrumb
  "Retrieve a string to describe a tree node in a path of breadcrumbs"
  (fn [node] (-> node .getKind)))
(defmulti to-string-verbose
  "Retrieve a detailed description of a tree node"
  (fn [node] (-> node .getKind)))
(defmulti get-icon
  "Retrieve the icon associated with a tree node"
  (fn [node] (-> node .getKind)))

(defmethod to-string :default [node]
  (-> node .getValue .toString))
(defmethod to-string :method [node]
  (str
    (-> node .getMethod .getName)
    "("
    (join (interpose ", " (map (memfn getSimpleName) (-> node .getMethod .getParameterTypes))))
    ") : "
    (-> node .getMethod .getReturnType .getSimpleName)))
(defmethod to-string :field [node]
  (str
    (-> node .getField .getName)
    " : "
    (-> node .getValue)))

(defn truncate [string length]
  "Returns a truncated string. If the string is longer than length, we only return the first 'length' characters and append an ellipsis to it."
  (if (> (count string) length)
    (str (subs string 0 length) "...")
    string))
(def crumb-length 20) ; Max string length of a breadcrumb node

(defmethod to-string-breadcrumb :default [node]
  (truncate 
    (-> node .getValue .toString)
    crumb-length))
(defmethod to-string-breadcrumb :method [node]
  (truncate (str
    (-> node .getMethod .getName)
    "("
    (join (interpose ", " (map (memfn getSimpleName) (-> node .getMethod .getParameterTypes))))
    ")")
    crumb-length))
(defmethod to-string-breadcrumb :field [node]
  (truncate (str (-> node .getField .getName)) 
    crumb-length))

(defmethod to-string-verbose :default [node]
  (str
    (-> node .getValue .getClass)
    "\n\n"
    (-> node .getValue)))
(defmethod to-string-verbose :method [node]
  (str
    (-> node .getMethod .toString)      
    (if (-> node .isValueAvailable)
           (str "\n\n" (-> node .getValue)))))
(defmethod to-string-verbose :field [node]
  (str 
    (-> node .getField)
    "\n\n"
    (-> node .getValue)))

(defmethod get-icon :default [node]
  (icon (resource "icons/genericvariable_obj.gif")))
(defmethod get-icon :method [node]
  (let
    [mod (-> node .getMethod .getModifiers)]
  (cond
    (Modifier/isPublic mod) (icon (resource "icons/methpub_obj.gif"))
    (Modifier/isPrivate mod) (icon (resource "icons/methpri_obj.gif"))
    (Modifier/isProtected mod) (icon (resource "icons/methpro_obj.gif"))
    :else (icon (resource "icons/methdef_obj.gif")))))
(defmethod get-icon :field [node]
  (let
    [mod (-> node .getField .getModifiers)]
  (cond
    (Modifier/isPublic mod) (icon (resource "icons/field_public_obj.gif"))
    (Modifier/isPrivate mod) (icon (resource "icons/field_private_obj.gif"))
    (Modifier/isProtected mod) (icon (resource "icons/field_protected_obj.gif"))
    :else (icon (resource "icons/field_default_obj.gif")))))

(defn tree-renderer ^DefaultTreeCellRenderer
  []
  "Returns a cell renderer which defines what each tree node should look like"
  (proxy [DefaultTreeCellRenderer] []
    (getTreeCellRendererComponent [tree value selected expanded leaf row hasFocus]
      (proxy-super getTreeCellRendererComponent tree value selected expanded leaf row hasFocus)
      (-> this (.setText (to-string value)))
      (-> this (.setIcon (get-icon value)))
      this)))

(defn tree-listener ^TreeSelectionListener
  [^JTextArea info-panel crumbs-panel]  
  "Update the detailed information panel, as well as the breadcrumbs, whenever a tree node is selected"
  (proxy [TreeSelectionListener] []
    (valueChanged [event]
      (config! info-panel :text (to-string-verbose (-> event .getNewLeadSelectionPath .getLastPathComponent)))
      (config! crumbs-panel :text 
        (str
          "<html>"
          (join(interpose "<font color=\"#268bd2\"><b> &gt; </b></font>" 
                 (map to-string-breadcrumb (-> event .getNewLeadSelectionPath .getPath))))
          "</html>")))))

(defn bindKeys
  [^JFrame frame ^JTree tree]
  "Attach various key bindings to frame, given tree"
  (let
    [f1Key (KeyStroke/getKeyStroke KeyEvent/VK_F1 0)
     escKey (KeyStroke/getKeyStroke KeyEvent/VK_ESCAPE 0)]
    ; Search javadoc for the currently selected node and open it in a browser window
    (-> frame .getRootPane (.registerKeyboardAction
                             (proxy [ActionListener] []
                               (actionPerformed [event]
                                 (javadoc (-> (-> tree .getLastSelectedPathComponent) .getValueClass))))
                                 f1Key
                                 JComponent/WHEN_IN_FOCUSED_WINDOW))
    ; Close the window
    (-> frame .getRootPane (.registerKeyboardAction
                             (proxy [ActionListener] []
                               (actionPerformed [event]
                                 (-> frame .dispose)))
                                 escKey
                                 JComponent/WHEN_IN_FOCUSED_WINDOW))))